package com.gole.api.shipping.application.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gole.api.common.exception.*;
import com.gole.api.shipping.application.port.out.*;
import com.gole.api.shipping.application.port.out.DeliveryTrackerPort.*;
import com.gole.api.shipping.domain.model.*;
import java.time.*;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrackerAdminServiceTest {
    private final DeliveryTrackerPort tracker = mock(DeliveryTrackerPort.class);
    private final TrackerCachePort cache = mock(TrackerCachePort.class);
    private final ShipmentRepositoryPort shipments = mock(ShipmentRepositoryPort.class);
    private final TrackerAdminAuditPort audit = mock(TrackerAdminAuditPort.class);
    private final Instant now = Instant.parse("2026-09-08T00:00:00Z");
    private final TrackerAdminService service =
            new TrackerAdminService(tracker, cache, shipments, audit, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void ready() {
        when(tracker.diagnostics()).thenReturn(new Diagnostics(true, true, false, null, null, null));
    }

    @Test
    void disabledDoesNotRunStub() {
        when(tracker.diagnostics()).thenReturn(new Diagnostics(false, false, false, null, null, null));
        var result = service.sample("admin", "hanjin", "123456789012");
        assertThat(result.live()).isFalse();
        assertThat(result.status()).isEqualTo(DeliveryStatus.UNKNOWN);
        verify(tracker, never()).track(any());
        verifyNoInteractions(audit, cache);
    }

    @Test
    void missingCredentialsDoNotCallProvider() {
        when(tracker.diagnostics()).thenReturn(new Diagnostics(true, false, false, null, null, "MISSING_CREDENTIALS"));
        assertThat(service.sample("admin", "hanjin", "123456789012").failure()).isEqualTo("MISSING_CREDENTIALS");
        service.verify("admin");
        verify(tracker, never()).verifyConnection();
        verify(tracker, never()).track(any());
    }

    @Test
    void cacheHitAuditedAndNoProviderCall() {
        when(cache.get(any(), any()))
                .thenReturn(Optional.of(new TrackingResult(DeliveryStatus.IN_TRANSIT, "IN_TRANSIT")));
        var result = service.sample("admin", "hanjin", "123456789012");
        assertThat(result.cached()).isTrue();
        assertThat(result.maskedWaybill()).isEqualTo("••••9012");
        verify(audit).record("admin", "SAMPLE_LOOKUP", "hanjin");
        verify(tracker, never()).track(any());
    }

    @Test
    void failureCachedBrieflyAndRateLimited() {
        when(cache.get(any(), any())).thenReturn(Optional.empty());
        when(tracker.track(any())).thenReturn(new TrackingResult(DeliveryStatus.UNKNOWN, null));
        assertThat(service.sample("admin", "hanjin", "123456789012").failure()).isEqualTo("TRACKING_UNAVAILABLE");
        verify(cache).put(any(), any(), any(), eq(Duration.ofSeconds(60)));
        assertThatThrownBy(() -> service.sample("admin", "hanjin", "123456789012"))
                .isInstanceOf(TooManyRequestsException.class);
        verify(tracker, times(1)).track(any());
    }

    @Test
    void auditFailurePreventsProviderCall() {
        doThrow(new IllegalStateException("DB unavailable")).when(audit).record(any(), any(), any());
        assertThatThrownBy(() -> service.sample("admin", "hanjin", "123456789012"))
                .isInstanceOf(IllegalStateException.class);
        verify(tracker, never()).track(any());
    }

    @Test
    void rejectsMissingActorAndInvalidInputs() {
        assertThatThrownBy(() -> service.sample("", "hanjin", "123456789012")).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.sample("admin", "http://localhost", "123456789012"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> service.refresh("admin", "../secret")).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(cache, audit, shipments);
    }

    @Test
    void safeRequeryNeverMutatesShipment() {
        Shipment shipment = Shipment.register(
                "ship", "order", "seller", "buyer", null, Carrier.HANJIN, new WaybillNumber("123456789012"), now);
        when(shipments.findByOrderId("order")).thenReturn(Optional.of(shipment));
        when(cache.get(any(), any()))
                .thenReturn(Optional.of(new TrackingResult(DeliveryStatus.DELIVERED, "DELIVERED")));
        assertThat(service.refresh("admin", "order").status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(shipment.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        verify(shipments, never()).save(any());
        verify(audit).record("admin", "SHIPMENT_REQUERY", "order");
    }

    @Test
    void liveDeliveredRequeryDoesNotPersistOrTriggerOrderFlow() {
        Shipment shipment = Shipment.register(
                "ship", "order", "seller", "buyer", null, Carrier.HANJIN, new WaybillNumber("123456789012"), now);
        when(shipments.findByOrderId("order")).thenReturn(Optional.of(shipment));
        when(cache.get(any(), any())).thenReturn(Optional.empty());
        when(tracker.track(any())).thenReturn(new TrackingResult(DeliveryStatus.DELIVERED, "DELIVERED"));

        var result = service.refresh("admin", "order");

        assertThat(result.status()).isEqualTo(DeliveryStatus.DELIVERED);
        assertThat(result.cached()).isFalse();
        assertThat(shipment.getStatus()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(shipment.getDeliveredAt()).isNull();
        verify(shipments).findByOrderId("order");
        verifyNoMoreInteractions(shipments);
        verify(audit).record("admin", "SHIPMENT_REQUERY", "order");
        verify(cache).put(any(), any(), any(), eq(Duration.ofMinutes(10)));
    }
}
