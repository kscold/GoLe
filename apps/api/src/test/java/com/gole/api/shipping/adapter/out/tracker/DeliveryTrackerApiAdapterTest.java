package com.gole.api.shipping.adapter.out.tracker;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.gole.api.shipping.application.port.out.DeliveryTrackerPort.TrackingQuery;
import com.gole.api.shipping.domain.model.*;
import java.net.http.*;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class DeliveryTrackerApiAdapterTest {
    private final HttpClient client = mock(HttpClient.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    private DeliveryTrackerApiAdapter adapter(String id, String secret) {
        return new DeliveryTrackerApiAdapter(
                new ObjectMapper(),
                "https://apis.tracker.delivery/graphql",
                id,
                secret,
                Duration.ofSeconds(5),
                client,
                clock);
    }

    @SuppressWarnings("unchecked")
    private void response(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response);
    }

    private TrackingQuery query() {
        return new TrackingQuery(Carrier.CJ_LOGISTICS, new WaybillNumber("123456789012"), clock.instant());
    }

    @Test
    void missingCredentialsNeverCallProvider() {
        var adapter = adapter("", "");
        assertThat(adapter.track(query()).status()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(adapter.diagnostics().lastFailure()).isEqualTo("MISSING_CREDENTIALS");
        assertThat(adapter.diagnostics().configured()).isFalse();
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @CsvSource({
        "INFORMATION_RECEIVED,PENDING",
        "AT_PICKUP,IN_TRANSIT",
        "IN_TRANSIT,IN_TRANSIT",
        "OUT_FOR_DELIVERY,IN_TRANSIT",
        "ATTEMPT_FAIL,IN_TRANSIT",
        "AVAILABLE_FOR_PICKUP,IN_TRANSIT",
        "DELIVERED,DELIVERED",
        "NEW_CODE,UNKNOWN"
    })
    void mapsStatuses(String code, DeliveryStatus expected) throws Exception {
        response(200, "{\"data\":{\"track\":{\"lastEvent\":{\"status\":{\"code\":\"" + code + "\"}}}}}");
        assertThat(adapter("id", "secret").track(query()).status()).isEqualTo(expected);
    }

    @Test
    void graphqlErrorsAreNotPendingEvenWithPartialData() throws Exception {
        response(
                200,
                "{\"errors\":[{\"message\":\"secret must not escape\"}],\"data\":{\"track\":{\"lastEvent\":null}}}");
        var adapter = adapter("id", "secret");
        assertThat(adapter.track(query()).status()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(adapter.diagnostics().lastFailure()).isEqualTo("GRAPHQL_ERROR");
        assertThat(new ObjectMapper().writeValueAsString(adapter.diagnostics())).doesNotContain("secret");
    }

    @Test
    void nullTrackIsUnknownButExplicitEmptyEventIsPending() throws Exception {
        response(200, "{\"data\":{\"track\":null}}");
        assertThat(adapter("id", "secret").track(query()).status()).isEqualTo(DeliveryStatus.UNKNOWN);
        response(200, "{\"data\":{\"track\":{\"lastEvent\":null}}}");
        assertThat(adapter("id", "secret").track(query()).status()).isEqualTo(DeliveryStatus.PENDING);
    }

    @Test
    void providerAuthFailureIsSanitized() throws Exception {
        response(401, "secret");
        var adapter = adapter("id", "secret");
        assertThat(adapter.track(query()).status()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(adapter.diagnostics().lastFailure()).isEqualTo("AUTHENTICATION_FAILED");
    }

    @Test
    void timeoutIsUnknown() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new HttpTimeoutException("secret"));
        var adapter = adapter("id", "secret");
        assertThat(adapter.track(query()).status()).isEqualTo(DeliveryStatus.UNKNOWN);
        assertThat(adapter.diagnostics().lastFailure()).isEqualTo("TIMEOUT");
    }

    @Test
    void concurrentPollingUsesOneProviderRequest() throws Exception {
        response(200, "{\"data\":{\"track\":{\"lastEvent\":null}}}");
        var adapter = adapter("id", "secret");
        try (var executor = Executors.newFixedThreadPool(4)) {
            var futures = executor.invokeAll(
                    List.of(() -> adapter.track(query()), () -> adapter.track(query()), () -> adapter.track(query())));
            for (var future : futures) assertThat(future.get()).isNotNull();
        }
        verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void connectionVerificationUsesAuthAndCooldown() throws Exception {
        response(200, "{\"data\":{\"carriers\":{\"edges\":[]}}}");
        var adapter = adapter("id", "secret");
        assertThat(adapter.verifyConnection().connected()).isTrue();
        assertThat(adapter.verifyConnection().connected()).isTrue();
        var request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client, times(1)).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://apis.tracker.delivery/graphql");
        assertThat(request.getValue().headers().firstValue("Authorization")).contains("TRACKQL-API-KEY id:secret");
        assertThat(request.getValue().timeout()).contains(Duration.ofSeconds(5));
    }

    @Test
    void arbitraryEndpointRejected() {
        assertThatThrownBy(() -> new DeliveryTrackerApiAdapter(
                        new ObjectMapper(),
                        "http://127.0.0.1/private",
                        "id",
                        "secret",
                        Duration.ofSeconds(5),
                        client,
                        clock))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(client);
    }

    @Test
    void cacheExpiresAfterSixtySeconds() throws Exception {
        Clock moving = mock(Clock.class);
        when(moving.instant()).thenReturn(clock.instant());
        response(200, "{\"data\":{\"track\":{\"lastEvent\":null}}}");
        var adapter = new DeliveryTrackerApiAdapter(
                new ObjectMapper(),
                "https://apis.tracker.delivery/graphql",
                "id",
                "secret",
                Duration.ofSeconds(5),
                client,
                moving);
        adapter.track(query());
        adapter.track(query());
        when(moving.instant()).thenReturn(clock.instant().plusSeconds(61));
        adapter.track(query());
        verify(client, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void globalBudgetLimitsDistinctWaybills() throws Exception {
        response(200, "{\"data\":{\"track\":{\"lastEvent\":null}}}");
        var adapter = adapter("id", "secret");
        for (int i = 0; i < 101; i++)
            adapter.track(new TrackingQuery(
                    Carrier.HANJIN, new WaybillNumber(Long.toString(123456780000L + i)), clock.instant()));
        verify(client, times(100)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertThat(adapter.diagnostics().lastFailure()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void providerRateLimitPausesOtherWaybills() throws Exception {
        response(429, "private upstream text");
        var adapter = adapter("id", "secret");
        adapter.track(query());
        assertThat(adapter.diagnostics().lastFailure()).isEqualTo("PROVIDER_RATE_LIMITED");
        adapter.track(new TrackingQuery(Carrier.HANJIN, new WaybillNumber("999999999999"), clock.instant()));
        verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void malformedBodyIsUnknown() throws Exception {
        response(200, "not json");
        assertThat(adapter("id", "secret").track(query()).status()).isEqualTo(DeliveryStatus.UNKNOWN);
    }
}
