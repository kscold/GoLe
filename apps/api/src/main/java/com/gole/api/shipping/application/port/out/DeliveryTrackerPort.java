package com.gole.api.shipping.application.port.out;

import com.gole.api.shipping.domain.model.Carrier;
import com.gole.api.shipping.domain.model.DeliveryStatus;
import com.gole.api.shipping.domain.model.WaybillNumber;
import java.time.Instant;

/**
 * Outbound port: 외부 배송 트래커. (R6.1)
 *
 * <p>외부 API 스펙 변화가 도메인에 새지 않도록 이 포트 뒤에 격리한다.
 * 조회 실패는 예외가 아니라 {@code UNKNOWN} 결과로 접는다(R2.3 graceful degradation).
 */
public interface DeliveryTrackerPort {

    /** 실 트래커 자격증명이 설정되어 있는가. 스텁은 false를 반환한다. */
    boolean isConfigured();

    TrackingResult track(TrackingQuery query);

    default Diagnostics diagnostics() {
        return new Diagnostics(false, isConfigured(), false, null, null, null);
    }

    default Diagnostics verifyConnection() {
        return diagnostics();
    }

    record Diagnostics(
            boolean enabled,
            boolean configured,
            boolean connected,
            Instant lastSuccessAt,
            Instant lastFailureAt,
            String lastFailure) {}

    /**
     * @param registeredAt 운송장 등록 시각. 스텁이 경과 시간 기반 시뮬레이션에 쓴다(R6.3).
     *                     실 어댑터는 무시한다.
     */
    record TrackingQuery(Carrier carrier, WaybillNumber waybill, Instant registeredAt) {}

    /**
     * @param rawStatus 택배사 원문 상태(보존용, R2.2). 없으면 null.
     */
    record TrackingResult(DeliveryStatus status, String rawStatus) {}
}
