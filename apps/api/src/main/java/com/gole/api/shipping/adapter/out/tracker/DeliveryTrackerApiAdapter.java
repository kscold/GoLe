package com.gole.api.shipping.adapter.out.tracker;

import com.gole.api.shipping.application.port.out.DeliveryTrackerPort;
import com.gole.api.shipping.domain.model.DeliveryStatus;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivery Tracker(tracker.delivery) 실 어댑터. (R6, F2/F3)
 *
 * <p>{@code shipping.tracker.enabled=true} + 클라이언트 자격증명이 있을 때만 활성화된다.
 * GraphQL 단일 엔드포인트에 {@code track(carrierId, trackingNumber)}를 질의하고,
 * 표준 상태 코드를 도메인 {@link DeliveryStatus}로 정규화한다. 원문 상태명은 그대로 보존한다(R2.2).
 *
 * <p>모든 실패(네트워크·인증·미지원 송장)는 {@code UNKNOWN}으로 접는다 — 조회 실패가
 * 주문 흐름을 막으면 안 된다(R2.3). 연속 UNKNOWN은 파이프라인이 예외 큐로 올린다.
 */
@Component
@ConditionalOnProperty(name = "shipping.tracker.enabled", havingValue = "true")
public class DeliveryTrackerApiAdapter implements DeliveryTrackerPort {

    /**
     * Delivery Tracker 표준 상태 코드 → 도메인 상태. (F3 매핑 테이블)
     * 목록에 없는 코드는 UNKNOWN — 새 코드가 추가되어도 오동작 대신 예외 큐로 흘러간다.
     */
    private static final Map<String, DeliveryStatus> STATUS_MAP = Map.of(
            "INFORMATION_RECEIVED", DeliveryStatus.PENDING,
            "AT_PICKUP", DeliveryStatus.IN_TRANSIT,
            "IN_TRANSIT", DeliveryStatus.IN_TRANSIT,
            "OUT_FOR_DELIVERY", DeliveryStatus.IN_TRANSIT,
            "ATTEMPT_FAIL", DeliveryStatus.IN_TRANSIT,
            "AVAILABLE_FOR_PICKUP", DeliveryStatus.IN_TRANSIT,
            "DELIVERED", DeliveryStatus.DELIVERED);

    private static final String TRACK_QUERY =
            """
            query Track($carrierId: ID!, $trackingNumber: String!) {
              track(carrierId: $carrierId, trackingNumber: $trackingNumber) {
                lastEvent { status { code name } }
              }
            }""";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final URI endpoint;
    private final String clientId;
    private final String clientSecret;
    private final Duration timeout;

    private final Clock clock;
    private final Map<String, Cached> recent = new LinkedHashMap<>();
    private Instant lastSuccessAt;
    private Instant lastFailureAt;
    private String lastFailure;
    private Instant nextRequestAt = Instant.EPOCH;
    private Instant requestWindow = Instant.EPOCH;
    private int requestCount;
    private Instant nextVerificationAt = Instant.EPOCH;
    private boolean connected;

    private record Cached(TrackingResult result, Instant expiresAt) {}

    @Autowired
    public DeliveryTrackerApiAdapter(
            ObjectMapper objectMapper,
            @Value("${shipping.tracker.api-base:https://apis.tracker.delivery/graphql}") String apiBase,
            @Value("${shipping.tracker.client-id:}") String clientId,
            @Value("${shipping.tracker.client-secret:}") String clientSecret,
            @Value("${shipping.tracker.timeout:PT5S}") Duration timeout) {
        this(
                objectMapper,
                apiBase,
                clientId,
                clientSecret,
                timeout,
                HttpClient.newBuilder()
                        .connectTimeout(timeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Clock.systemUTC());
    }

    DeliveryTrackerApiAdapter(
            ObjectMapper objectMapper,
            String apiBase,
            String clientId,
            String clientSecret,
            Duration timeout,
            HttpClient httpClient,
            Clock clock) {
        if (!"https://apis.tracker.delivery/graphql".equals(apiBase)) {
            throw new IllegalArgumentException(
                    "Delivery Tracker endpoint must be https://apis.tracker.delivery/graphql");
        }
        if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofSeconds(15)) > 0) {
            throw new IllegalArgumentException("Tracker timeout must be within 0–15 seconds");
        }
        this.httpClient = httpClient;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.endpoint = URI.create(apiBase);
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.timeout = timeout;
    }

    @Override
    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank();
    }

    @Override
    public synchronized Diagnostics diagnostics() {
        return new Diagnostics(true, isConfigured(), connected, lastSuccessAt, lastFailureAt, lastFailure);
    }

    @Override
    public synchronized Diagnostics verifyConnection() {
        Instant now = clock.instant();
        if (now.isBefore(nextVerificationAt)) return diagnostics();
        nextVerificationAt = now.plusSeconds(60);
        JsonNode root = request("query { carriers(first: 1) { edges { node { id } } } }", Map.of());
        if (root != null) {
            if (root.path("data").path("carriers").isObject()) success();
            else failure("INVALID_RESPONSE");
        }
        return diagnostics();
    }

    /** Single-flight and bounded cooldown also protect scheduler/cache-outage paths. */
    @Override
    public synchronized TrackingResult track(TrackingQuery query) {
        String key = query.carrier().name() + ":" + query.waybill().value();
        Instant now = clock.instant();
        Cached cached = recent.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) return cached.result();
        JsonNode root = request(
                TRACK_QUERY,
                Map.of(
                        "carrierId",
                        query.carrier().trackerId(),
                        "trackingNumber",
                        query.waybill().value()));
        TrackingResult result = root == null ? unknown() : parse(root);
        recent.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        if (recent.size() >= 1000) recent.remove(recent.keySet().iterator().next());
        recent.put(key, new Cached(result, now.plusSeconds(60)));
        return result;
    }

    private JsonNode request(String query, Map<String, String> variables) {
        if (!isConfigured()) {
            failure("MISSING_CREDENTIALS");
            return null;
        }
        Instant now = clock.instant();
        if (now.isBefore(nextRequestAt)) {
            failure("RATE_LIMITED");
            return null;
        }
        if (!now.isBefore(requestWindow.plusSeconds(60))) {
            requestWindow = now;
            requestCount = 0;
        }
        if (requestCount >= 100) {
            failure("RATE_LIMITED");
            return null;
        }
        requestCount++;
        try {
            String body = objectMapper.writeValueAsString(Map.of("query", query, "variables", variables));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "TRACKQL-API-KEY " + clientId + ":" + clientSecret)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                failure(
                        response.statusCode() == 401 || response.statusCode() == 403
                                ? "AUTHENTICATION_FAILED"
                                : response.statusCode() == 429 ? "PROVIDER_RATE_LIMITED" : "PROVIDER_UNAVAILABLE");
                nextRequestAt = now.plusSeconds(60);
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            if (root == null || !root.isObject()) {
                failure("INVALID_RESPONSE");
                return null;
            }
            JsonNode errors = root.path("errors");
            if (!errors.isMissingNode() && !errors.isNull() && (!errors.isArray() || !errors.isEmpty())) {
                failure("GRAPHQL_ERROR");
                nextRequestAt = now.plusSeconds(60);
                return null;
            }
            return root;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure("INTERRUPTED");
        } catch (java.net.http.HttpTimeoutException e) {
            failure("TIMEOUT");
        } catch (Exception e) {
            // Never log provider bodies, exception messages, credentials or waybill numbers.
            failure("PROVIDER_UNAVAILABLE");
        }
        return null;
    }

    private TrackingResult parse(JsonNode root) {
        JsonNode track = root.path("data").path("track");
        if (!track.isObject() || !track.has("lastEvent")) {
            failure("TRACK_NOT_FOUND");
            return unknown();
        }
        JsonNode event = track.path("lastEvent");
        if (event.isNull()) {
            success();
            return new TrackingResult(DeliveryStatus.PENDING, null);
        }
        String code = event.path("status").path("code").asString("");
        DeliveryStatus status = STATUS_MAP.getOrDefault(code, DeliveryStatus.UNKNOWN);
        if (status == DeliveryStatus.UNKNOWN) {
            failure("UNKNOWN_STATUS");
            return unknown();
        }
        success();
        String name = event.path("status").path("name").asString(null);
        return new TrackingResult(status, name == null || name.isBlank() ? code : name);
    }

    private void success() {
        connected = true;
        lastSuccessAt = clock.instant();
    }

    private void failure(String code) {
        connected = false;
        lastFailureAt = clock.instant();
        lastFailure = code;
    }

    private TrackingResult unknown() {
        return new TrackingResult(DeliveryStatus.UNKNOWN, null);
    }
}
