package com.gole.api.shipping.application.service;

import com.gole.api.common.exception.BadRequestException;
import com.gole.api.common.exception.ForbiddenException;
import com.gole.api.common.exception.TooManyRequestsException;
import com.gole.api.shipping.application.port.in.ManageTrackerUseCase;
import com.gole.api.shipping.application.port.out.*;
import com.gole.api.shipping.application.port.out.DeliveryTrackerPort.*;
import com.gole.api.shipping.domain.exception.ShipmentNotFoundException;
import com.gole.api.shipping.domain.model.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

@Service
public class TrackerAdminService implements ManageTrackerUseCase {
    private final DeliveryTrackerPort tracker;
    private final TrackerCachePort cache;
    private final ShipmentRepositoryPort shipments;
    private final TrackerAdminAuditPort audit;
    private final Clock clock;
    private Instant nextActionAt = Instant.EPOCH;

    public TrackerAdminService(
            DeliveryTrackerPort tracker,
            TrackerCachePort cache,
            ShipmentRepositoryPort shipments,
            TrackerAdminAuditPort audit,
            Clock clock) {
        this.tracker = tracker;
        this.cache = cache;
        this.shipments = shipments;
        this.audit = audit;
        this.clock = clock;
    }

    public Diagnostics status() {
        return tracker.diagnostics();
    }

    public synchronized Diagnostics verify(String actorId) {
        authorize(actorId);
        if (!ready()) return status();
        reserve();
        audit.record(actorId, "VERIFY_CONNECTION", "tracker.delivery");
        return tracker.verifyConnection();
    }

    public synchronized Sample sample(String actorId, String carrier, String waybill) {
        authorize(actorId);
        Carrier parsed = Carrier.fromKey(carrier)
                .orElseThrow(() -> new BadRequestException("UNSUPPORTED_CARRIER", "지원하지 않는 택배사입니다"));
        return lookup(actorId, parsed, new WaybillNumber(waybill), "SAMPLE_LOOKUP", parsed.key());
    }

    public synchronized Sample refresh(String actorId, String orderId) {
        authorize(actorId);
        if (orderId == null || !orderId.matches("[a-zA-Z0-9_-]{1,100}"))
            throw new BadRequestException("INVALID_ORDER_ID", "주문 ID 형식을 확인해 주세요");
        Shipment shipment = shipments.findByOrderId(orderId).orElseThrow(() -> new ShipmentNotFoundException(orderId));
        return lookup(actorId, shipment.getCarrier(), shipment.getWaybill(), "SHIPMENT_REQUERY", orderId);
    }

    private Sample lookup(String actorId, Carrier carrier, WaybillNumber waybill, String action, String target) {
        String masked = "••••" + waybill.value().substring(waybill.value().length() - 4);
        if (!ready())
            return new Sample(
                    carrier.key(),
                    masked,
                    DeliveryStatus.UNKNOWN,
                    false,
                    false,
                    clock.instant(),
                    status().enabled() ? "MISSING_CREDENTIALS" : "DISABLED");
        reserve();
        audit.record(actorId, action, target);
        var cached = cache.get(carrier, waybill);
        TrackingResult result =
                cached.orElseGet(() -> tracker.track(new TrackingQuery(carrier, waybill, clock.instant())));
        if (cached.isEmpty())
            cache.put(
                    carrier, waybill, result, Duration.ofSeconds(result.status() == DeliveryStatus.UNKNOWN ? 60 : 600));
        return new Sample(
                carrier.key(),
                masked,
                result.status(),
                cached.isPresent(),
                true,
                clock.instant(),
                result.status() == DeliveryStatus.UNKNOWN ? "TRACKING_UNAVAILABLE" : null);
    }

    private boolean ready() {
        return status().enabled() && status().configured();
    }

    private void reserve() {
        if (clock.instant().isBefore(nextActionAt))
            throw new TooManyRequestsException(
                    "TRACKER_RATE_LIMITED",
                    "5초 후 다시 시도해 주세요. 캐시를 우회할 수 없습니다.",
                    Duration.between(clock.instant(), nextActionAt));
        nextActionAt = clock.instant().plusSeconds(5);
    }

    private void authorize(String actorId) {
        if (actorId == null || actorId.isBlank()) throw new ForbiddenException("ADMIN_ONLY", "관리자 권한이 필요합니다");
    }
}
