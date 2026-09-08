package com.gole.api.shipping.application.service;

import com.gole.api.common.exception.BadRequestException;
import com.gole.api.order.application.port.in.PrepareShipmentRegistrationUseCase;
import com.gole.api.order.domain.exception.OrderStateException;
import com.gole.api.order.domain.model.Order;
import com.gole.api.order.domain.model.PhoneNumber;
import com.gole.api.shipping.application.port.in.GetShipmentUseCase;
import com.gole.api.shipping.application.port.in.RegisterWaybillUseCase;
import com.gole.api.shipping.application.port.in.TrackShipmentUseCase;
import com.gole.api.shipping.application.port.out.DeliveryTrackerPort;
import com.gole.api.shipping.application.port.out.DeliveryTrackerPort.TrackingQuery;
import com.gole.api.shipping.application.port.out.DeliveryTrackerPort.TrackingResult;
import com.gole.api.shipping.application.port.out.ShipmentNotifierPort;
import com.gole.api.shipping.application.port.out.ShipmentRepositoryPort;
import com.gole.api.shipping.application.port.out.TrackerCachePort;
import com.gole.api.shipping.domain.exception.ShipmentNotFoundException;
import com.gole.api.shipping.domain.exception.ShipmentStateException;
import com.gole.api.shipping.domain.model.Carrier;
import com.gole.api.shipping.domain.model.DeliveryStatus;
import com.gole.api.shipping.domain.model.Shipment;
import com.gole.api.shipping.domain.model.WaybillNumber;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 배송 유스케이스. 운송장 등록(판매자 검증) · 트래커 조회 반영 · 조회.
 *
 * <p>order 컨텍스트는 {@link PrepareShipmentRegistrationUseCase} 인바운드 포트로만
 * 참조한다(NFR-3). 주문 펜스와 배송 문서는 하나의 Mongo 트랜잭션에서 함께 커밋한다.
 */
@Service
public class ShipmentService implements RegisterWaybillUseCase, TrackShipmentUseCase, GetShipmentUseCase {

    private final ShipmentRepositoryPort shipments;
    private final DeliveryTrackerPort tracker;
    private final TrackerCachePort trackerCache;
    private final ShipmentNotifierPort notifier;
    private final PrepareShipmentRegistrationUseCase prepareShipment;
    private final Clock clock;
    private final Duration activeCacheTtl;
    private final Duration deliveredCacheTtl;

    public ShipmentService(
            ShipmentRepositoryPort shipments,
            DeliveryTrackerPort tracker,
            TrackerCachePort trackerCache,
            ShipmentNotifierPort notifier,
            PrepareShipmentRegistrationUseCase prepareShipment,
            Clock clock,
            @Value("${shipping.tracker.cache-ttl-active:PT10M}") Duration activeCacheTtl,
            @Value("${shipping.tracker.cache-ttl-delivered:PT24H}") Duration deliveredCacheTtl) {
        this.shipments = shipments;
        this.tracker = tracker;
        this.trackerCache = trackerCache;
        this.notifier = notifier;
        this.prepareShipment = prepareShipment;
        this.clock = clock;
        this.activeCacheTtl = activeCacheTtl;
        this.deliveredCacheTtl = deliveredCacheTtl;
    }

    @Override
    @Transactional
    public Shipment register(RegisterWaybillCommand command) {
        Carrier carrier = Carrier.fromKey(command.carrierKey())
                .orElseThrow(() ->
                        new BadRequestException("UNSUPPORTED_CARRIER", "지원하지 않는 택배사입니다: " + command.carrierKey()));
        WaybillNumber waybill = new WaybillNumber(command.waybill());
        String sellerPhone =
                command.sellerPhone() == null || command.sellerPhone().isBlank()
                        ? null
                        : new PhoneNumber(command.sellerPhone()).value();
        Instant now = Instant.now(clock);
        // 주문 문서의 버전을 먼저 갱신한다. 환불이 선점했다면 여기서 거부되고, 이후 배송
        // 저장이 실패해도 바깥 트랜잭션이 펜스까지 함께 롤백한다.
        Order order;
        try {
            order = prepareShipment.prepare(command.orderId(), command.sellerId());
        } catch (OrderStateException invalidOrderState) {
            // 기존 배송 API의 409 오류 계약을 유지한다. 낙관적 락 충돌은 이 예외가 아니므로
            // 전역 CONCURRENT_UPDATE_CONFLICT 응답으로 그대로 전달된다.
            throw new ShipmentStateException("결제 승인이 확인된 주문에만 운송장을 등록할 수 있습니다");
        }

        Shipment shipment = shipments
                .findByOrderId(command.orderId())
                .map(existing -> {
                    existing.replaceWaybill(carrier, waybill, sellerPhone, now);
                    return existing;
                })
                .orElseGet(() -> Shipment.register(
                        UUID.randomUUID().toString(),
                        order.getId(),
                        order.getSellerId(),
                        order.getBuyerId(),
                        sellerPhone,
                        carrier,
                        waybill,
                        now));
        Shipment saved = shipments.save(shipment);
        // R1.5: 구매자 알림(best-effort — 어댑터가 실패를 흡수한다)
        notifier.notifyWaybillRegistered(order.getBuyerId(), order.getId(), carrier.label(), waybill.value());
        return saved;
    }

    @Override
    public synchronized Shipment track(String orderId) {
        Shipment shipment = shipments.findByOrderId(orderId).orElseThrow(() -> new ShipmentNotFoundException(orderId));
        if (shipment.getStatus() == DeliveryStatus.DELIVERED) {
            return shipment; // 종결 상태 — 외부 조회 불필요
        }
        TrackingResult result = lookup(shipment);
        boolean newlyDelivered = shipment.applyTracking(result.status(), result.rawStatus(), Instant.now(clock));
        Shipment saved = shipments.save(shipment);
        if (newlyDelivered) {
            // R2.4: 배송 완료 알림(구매자·판매자)
            notifier.notifyDelivered(saved.getBuyerId(), saved.getSellerId(), saved.getOrderId());
        }
        return saved;
    }

    /**
     * 캐시를 거친 트래커 조회. (R2.5)
     *
     * <p>스텁 트래커({@code isConfigured() == false})는 호출 비용이 없으므로 캐시를 건너뛴다 —
     * 캐시를 끼우면 로컬에서 상태 전이 시뮬레이션이 TTL만큼 늦게 보인다.
     */
    private TrackingResult lookup(Shipment shipment) {
        if (!tracker.isConfigured()) {
            return tracker.track(
                    new TrackingQuery(shipment.getCarrier(), shipment.getWaybill(), shipment.getRegisteredAt()));
        }
        Optional<TrackingResult> cached = trackerCache.get(shipment.getCarrier(), shipment.getWaybill());
        if (cached.isPresent()) {
            return cached.get();
        }
        TrackingResult result = tracker.track(
                new TrackingQuery(shipment.getCarrier(), shipment.getWaybill(), shipment.getRegisteredAt()));
        Duration ttl = result.status() == DeliveryStatus.UNKNOWN
                ? Duration.ofSeconds(60)
                : result.status() == DeliveryStatus.DELIVERED ? deliveredCacheTtl : activeCacheTtl;
        trackerCache.put(shipment.getCarrier(), shipment.getWaybill(), result, ttl);
        return result;
    }

    @Override
    public Optional<Shipment> getByOrderId(String orderId) {
        return shipments.findByOrderId(orderId);
    }

    @Override
    public List<Shipment> findDeliveredBefore(Instant cutoff) {
        return shipments.findByStatusAndDeliveredAtBefore(DeliveryStatus.DELIVERED, cutoff);
    }

    @Override
    public List<Shipment> findPendingRegisteredBefore(Instant cutoff) {
        return shipments.findByStatusAndRegisteredAtBefore(DeliveryStatus.PENDING, cutoff);
    }

    @Override
    public List<Shipment> findInTransitStalledSince(Instant cutoff) {
        return shipments.findByStatusAndStatusChangedAtBefore(DeliveryStatus.IN_TRANSIT, cutoff);
    }

    @Override
    public List<Shipment> findUnknownSince(Instant cutoff) {
        return shipments.findByUnknownSinceBefore(cutoff);
    }
}
