package com.gole.api.shipping.application.port.in;

import com.gole.api.shipping.application.port.out.DeliveryTrackerPort.Diagnostics;
import com.gole.api.shipping.domain.model.DeliveryStatus;
import java.time.Instant;

public interface ManageTrackerUseCase {
    Diagnostics status();

    Diagnostics verify(String actorId);

    Sample sample(String actorId, String carrier, String waybill);

    Sample refresh(String actorId, String orderId);

    record Sample(
            String carrier,
            String maskedWaybill,
            DeliveryStatus status,
            boolean cached,
            boolean live,
            Instant checkedAt,
            String failure) {}
}
