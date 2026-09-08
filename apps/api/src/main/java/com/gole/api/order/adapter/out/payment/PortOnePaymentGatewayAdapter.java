package com.gole.api.order.adapter.out.payment;

import com.gole.api.common.operations.OperationalEvent;
import com.gole.api.common.operations.OperationalEvent.Category;
import com.gole.api.common.operations.OperationalEvent.Level;
import com.gole.api.common.operations.OperationalEventPublisher;
import com.gole.api.order.application.port.out.PaymentGatewayPort;
import com.gole.api.order.application.port.out.PaymentGatewayUnavailableException;
import com.gole.api.order.application.port.out.PaymentReviewRequiredException;
import com.gole.api.order.domain.model.PaymentEvidenceKind;
import com.gole.api.order.domain.model.PaymentMethod;
import com.gole.api.order.domain.model.PaymentMethodType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * 포트원(PortOne) V2 결제 게이트웨이 어댑터.
 *
 * <p>결제는 프론트의 브라우저 SDK가 수행하고, 서버는 결과를 <b>검증</b>한다(verify-on-server).
 * 우리 주문 id를 포트원 {@code paymentId}로 사용하므로 {@code verifyPayment(orderId, amount)}에서
 * {@code GET /payments/{orderId}} 로 결제 상태(PAID)와 금액 일치를 확인한다.
 *
 * <p>활성화: {@code portone.enabled=true} + API secret·상점·채널 설정 필요.
 * 미설정 시 {@link StubPaymentGatewayAdapter}가 사용된다.
 *
 * <p>모든 PortOne 호출에는 연결·읽기 타임아웃이 걸린다. 기본 {@code RestClient}는 무한 대기라
 * PortOne이 응답을 끊지 않고 멈추면 결제 확인 요청과 웹훅 스레드가 영원히 묶이고, 웹훅은
 * PortOne 쪽 타임아웃으로 실패 처리돼 재시도가 또 같은 스레드를 잡는다. 타임아웃은
 * {@link PaymentGatewayUnavailableException}으로 끝나므로 호출측은 재시도 가능한 장애로 본다.
 */
@Component
@ConditionalOnProperty(name = "portone.enabled", havingValue = "true")
public class PortOnePaymentGatewayAdapter implements PaymentGatewayPort {

    private static final Logger log = LoggerFactory.getLogger(PortOnePaymentGatewayAdapter.class);

    private final RestClient client;
    private final OperationalEventPublisher operationalEvents;
    private final String expectedStoreId;
    private final List<AllowedChannel> allowedChannels;
    private final String expectedChannelType;

    public PortOnePaymentGatewayAdapter(
            @Value("${portone.api-base:https://api.portone.io}") String apiBase,
            @Value("${portone.api-secret}") String apiSecret,
            @Value("${portone.store-id}") String expectedStoreId,
            @Value("${portone.channel-key}") String expectedChannelKey,
            @Value("${portone.card-channel-key:}") String cardChannelKey,
            @Value("${portone.channel-type:TEST}") String expectedChannelType,
            @Value("${portone.connect-timeout:PT3S}") Duration connectTimeout,
            @Value("${portone.read-timeout:PT10S}") Duration readTimeout,
            OperationalEventPublisher operationalEvents) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(requirePositive("portone.connect-timeout", connectTimeout));
        requestFactory.setReadTimeout(requirePositive("portone.read-timeout", readTimeout));
        this.client = RestClient.builder()
                .baseUrl(apiBase)
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "PortOne " + apiSecret)
                .build();
        this.expectedStoreId = expectedStoreId.trim();
        this.allowedChannels = allowedChannels(expectedChannelKey, cardChannelKey);
        this.expectedChannelType = expectedChannelType.trim().toUpperCase(Locale.ROOT);
        this.operationalEvents = operationalEvents;
    }

    /**
     * 채널 키와 그 채널이 낼 수 있는 결제수단을 <b>한 쌍으로</b> 묶는다.
     *
     * <p>둘을 따로 검사하면 조건이 "허용 채널 중 하나" ∧ "허용 수단 중 하나"로 느슨해져서,
     * 카카오페이 채널로 낸 카드 결제처럼 우리가 계약하지 않은 조합이 통과한다. 결제수단을
     * 늘리는 변경에서 실제로 위험한 지점은 여기 하나다.
     *
     * @param provider 간편결제 사업자. 사업자 구분이 없는 수단(카드 등)은 null.
     * @param label 로그·알림에 쓰는 사람이 읽는 이름
     */
    private record AllowedChannel(String key, PaymentMethodType type, String provider, String label) {}

    private static List<AllowedChannel> allowedChannels(String kakaoPayChannelKey, String cardChannelKey) {
        List<AllowedChannel> channels = new ArrayList<>();
        channels.add(
                new AllowedChannel(kakaoPayChannelKey.trim(), PaymentMethodType.EASY_PAY, "KAKAOPAY", "간편결제/KAKAOPAY"));
        // 카드 채널은 선택 설정이다. 비어 있으면 카드 원장은 어느 튜플과도 맞지 않아 승인되지 않는다.
        if (!cardChannelKey.isBlank()) {
            channels.add(new AllowedChannel(cardChannelKey.trim(), PaymentMethodType.CARD, null, "카드"));
        }
        return List.copyOf(channels);
    }

    private static Duration requirePositive(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(property + " must be a positive duration");
        }
        return value;
    }

    @Override
    public void preparePayment(String orderId, long amount) {
        try {
            client.post()
                    .uri("/payments/{paymentId}/pre-register", orderId)
                    .body(Map.of(
                            "storeId", expectedStoreId,
                            "totalAmount", amount,
                            "currency", "KRW"))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new IllegalStateException("PortOne pre-register failed: " + res.getStatusCode());
                    })
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.error("[PortOne] 결제 사전 등록 실패 orderId={}: {}", orderId, ex.getMessage());
            throw new PaymentGatewayUnavailableException(orderId, ex);
        }
    }

    @Override
    public PaymentVerification verifyPayment(String orderId, long amount) {
        try {
            Map<?, ?> payment = fetchPayment(orderId);
            String status = normalizedText(payment.get("status"));
            if (status == null) {
                log.warn("[PortOne] 결제 상태 누락 orderId={}", orderId);
                publishReviewRequired(orderId, "PG 상태 누락", null);
                return PaymentVerification.of(PaymentVerificationResult.REVIEW_REQUIRED);
            }
            String provenanceFailure = findPaymentProvenanceFailure(payment, orderId, amount);
            if (provenanceFailure != null) {
                logLedgerMismatch("결제 원장 기본 검증 실패", orderId, provenanceFailure, amount, payment);
                publishReviewRequired(orderId, provenanceFailure, status, payment);
                return PaymentVerification.of(PaymentVerificationResult.REVIEW_REQUIRED);
            }
            if ("PAID".equals(status)) {
                String validationFailure = findPaymentValidationFailure(payment, orderId, amount);
                if (validationFailure == null) {
                    // 검증을 통과한 원장에서만 결제수단을 읽는다. 위 검증이 이미 method를
                    // 확인했으므로 추가 조회 없이 사실을 그대로 넘긴다.
                    PaymentMethod method = PortOnePaymentMethodMapper.from(payment);
                    log.info(
                            "[PortOne] 결제 승인 확인 orderId={} amount={} method={}/{}",
                            orderId,
                            amount,
                            method.type(),
                            method.provider());
                    PaymentEvidenceKind evidenceKind =
                            "LIVE".equals(expectedChannelType) ? PaymentEvidenceKind.LIVE : PaymentEvidenceKind.TEST;
                    return PaymentVerification.paid(method, evidenceKind);
                }
                logLedgerMismatch("결제 원장 검증 실패", orderId, validationFailure, amount, payment);
                publishReviewRequired(orderId, validationFailure, status, payment);
                return PaymentVerification.of(PaymentVerificationResult.REVIEW_REQUIRED);
            }
            if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                // 실패 결제는 method/channel이 없을 수 있지만, 예약을 해제하는 최종 전이 전에
                // 위에서 최소 provenance(ID·상점·V2·통화·금액)를 검증한 뒤에만 실패로 확정한다.
                return PaymentVerification.of(PaymentVerificationResult.FAILED);
            }
            return switch (status) {
                    // 결제 단건 조회 응답의 discriminator는 PAY_PENDING이다. PENDING은
                    // PaymentStatus 필터 값과 이전 응답에 대한 안전한 호환으로만 허용한다.
                case "READY", "PAY_PENDING", "PENDING" -> PaymentVerification.of(PaymentVerificationResult.PENDING);
                default -> {
                    log.warn("[PortOne] 알 수 없는 결제 상태 orderId={} status={}", orderId, status);
                    publishReviewRequired(orderId, "알 수 없는 PG 상태", status);
                    yield PaymentVerification.of(PaymentVerificationResult.REVIEW_REQUIRED);
                }
            };
        } catch (HttpClientErrorException.NotFound ex) {
            // 결제창을 열기 전 주문 상세를 조회한 경우 PortOne에는 paymentId가 아직 없다.
            // 즉시 실패로 확정하면 정상적인 결제 재시도를 막으므로 별도 상태로 돌려준다.
            log.info("[PortOne] 결제 건 없음 orderId={}", orderId);
            return PaymentVerification.of(PaymentVerificationResult.NOT_FOUND);
        } catch (Exception ex) {
            log.error("[PortOne] 결제 조회 실패 orderId={}: {}", orderId, ex.getMessage());
            // 조회 실패는 결제 거절이 아니다. false를 반환하면 매물 선점이 잘못 풀리므로 재시도 가능한 예외로 분리한다.
            throw new PaymentGatewayUnavailableException(orderId, ex);
        }
    }

    @Override
    public RefundResult refund(String orderId, long amount) {
        try {
            Map<?, ?> payment = fetchPayment(orderId);
            String status = normalizedText(payment.get("status"));
            String validationFailure = findPaymentValidationFailure(payment, orderId, amount);
            if (validationFailure != null) {
                logLedgerMismatch("환불 전 원장 검증 실패", orderId, validationFailure, amount, payment);
                publishReviewRequired(orderId, "환불 전 " + validationFailure, status, payment);
                throw new PaymentReviewRequiredException();
            }
            if ("CANCELLED".equals(status)) {
                if (extractCancelledTotal(payment) != amount) {
                    publishReviewRequired(orderId, "기존 전액 환불 금액 불일치 또는 누락", status);
                    throw new PaymentReviewRequiredException();
                }
                return RefundResult.SUCCEEDED;
            }
            if (hasRequestedFullCancellation(payment, orderId, amount)) {
                // 비동기 취소는 결제 상태가 아직 PAID일 수 있다. 동일 취소를 다시 만들지 않고
                // Transaction.Cancelled 웹훅 또는 다음 원장 재조정을 기다린다.
                return RefundResult.REQUESTED;
            }
            if (!"PAID".equals(status)) {
                publishReviewRequired(orderId, "자동 환불할 수 없는 PG 상태", status);
                throw new PaymentReviewRequiredException();
            }

            Map<?, ?> response = client.post()
                    .uri("/payments/{paymentId}/cancel", orderId)
                    .body(Map.of(
                            "storeId", expectedStoreId,
                            "reason", "주문 환불",
                            "requester", "CUSTOMER",
                            "amount", amount,
                            "currentCancellableAmount", amount))
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        throw new IllegalStateException("PortOne cancel failed: " + res.getStatusCode());
                    })
                    .body(Map.class);
            String cancellationStatus = extractCancellationStatus(response);
            long cancellationTotal = extractCancellationTotal(response);
            if (cancellationTotal != amount) {
                publishReviewRequired(orderId, "환불 응답 금액 불일치 또는 누락", cancellationStatus);
                throw new PaymentReviewRequiredException();
            }
            RefundResult result =
                    switch (cancellationStatus) {
                        case "SUCCEEDED" -> RefundResult.SUCCEEDED;
                        case "REQUESTED" -> RefundResult.REQUESTED;
                        case "FAILED" -> {
                            publishReviewRequired(orderId, "PG 환불 실패", cancellationStatus);
                            throw new PaymentReviewRequiredException();
                        }
                        default -> {
                            publishReviewRequired(orderId, "알 수 없는 PG 환불 상태", cancellationStatus);
                            throw new PaymentReviewRequiredException();
                        }
                    };
            log.info("[PortOne] 환불 응답 orderId={} amount={} status={}", orderId, amount, cancellationStatus);
            return result;
        } catch (PaymentReviewRequiredException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("[PortOne] 환불 실패 orderId={}: {}", orderId, ex.getMessage());
            throw new PaymentGatewayUnavailableException(orderId, ex);
        }
    }

    @Override
    public boolean isFullyRefunded(String orderId, long amount) {
        try {
            Map<?, ?> payment = fetchPayment(orderId);
            String status = normalizedText(payment.get("status"));
            String validationFailure = findPaymentValidationFailure(payment, orderId, amount);
            if (validationFailure != null) {
                logLedgerMismatch("환불 확정 전 원장 검증 실패", orderId, validationFailure, amount, payment);
                publishReviewRequired(orderId, "환불 확정 전 " + validationFailure, status, payment);
                throw new PaymentReviewRequiredException();
            }
            if ("CANCELLED".equals(status) && extractCancelledTotal(payment) != amount) {
                publishReviewRequired(orderId, "전액 환불 금액 불일치 또는 누락", status);
                throw new PaymentReviewRequiredException();
            }
            return "CANCELLED".equals(status);
        } catch (PaymentReviewRequiredException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new PaymentGatewayUnavailableException(orderId, ex);
        }
    }

    private Map<?, ?> fetchPayment(String orderId) {
        Map<?, ?> payment =
                client.get().uri("/payments/{paymentId}", orderId).retrieve().body(Map.class);
        if (payment == null) {
            throw new IllegalStateException("PortOne payment response is empty");
        }
        return payment;
    }

    private static String extractCancellationStatus(Map<?, ?> response) {
        if (response != null && response.get("cancellation") instanceof Map<?, ?> cancellation) {
            String status = normalizedText(cancellation.get("status"));
            return status == null ? "UNKNOWN" : status;
        }
        return "UNKNOWN";
    }

    private static long extractCancellationTotal(Map<?, ?> response) {
        if (response != null && response.get("cancellation") instanceof Map<?, ?> cancellation) {
            return number(cancellation.get("totalAmount"));
        }
        return -1;
    }

    private static long extractPaidTotal(Map<?, ?> payment) {
        Object amountObj = payment.get("amount");
        if (amountObj instanceof Map<?, ?> amountMap) {
            return number(amountMap.get("total"));
        }
        return -1;
    }

    private static long extractCancelledTotal(Map<?, ?> payment) {
        Object amountObj = payment.get("amount");
        if (amountObj instanceof Map<?, ?> amountMap) {
            return number(amountMap.get("cancelled"));
        }
        return -1;
    }

    private boolean hasRequestedFullCancellation(Map<?, ?> payment, String orderId, long amount) {
        if (!(payment.get("cancellations") instanceof List<?> cancellations)) {
            return false;
        }
        for (Object value : cancellations) {
            if (!(value instanceof Map<?, ?> cancellation)
                    || !"REQUESTED".equals(normalizedText(cancellation.get("status")))) {
                continue;
            }
            if (number(cancellation.get("totalAmount")) != amount) {
                publishReviewRequired(orderId, "진행 중인 환불 금액 불일치 또는 누락", "REQUESTED", payment);
                throw new PaymentReviewRequiredException();
            }
            return true;
        }
        return false;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : -1;
    }

    /**
     * PAID 원장을 주문 상태에 반영하기 전에 상점·채널·통화·결제수단을 모두 고정한다.
     *
     * <p>PortOne V2 결제 조회 응답은 선택 채널을 {@code channel}, 결제수단을 {@code method}에
     * 담는다. 스키마가 바뀌거나 필드가 누락된 경우에도 승인하지 않고 수동 검토로 보낸다.
     *
     * <p><b>기대하는 결제수단은 원장의 채널이 정한다.</b> 허용 목록에서 채널을 먼저 특정하고,
     * 그 채널이 요구하는 수단만 인정한다({@link AllowedChannel}).
     */
    private String findPaymentValidationFailure(Map<?, ?> payment, String expectedPaymentId, long expectedAmount) {
        String provenanceFailure = findPaymentProvenanceFailure(payment, expectedPaymentId, expectedAmount);
        if (provenanceFailure != null) {
            return provenanceFailure;
        }
        if (!(payment.get("channel") instanceof Map<?, ?> channel)) {
            return "결제 채널 정보 누락";
        }
        AllowedChannel allowed = findAllowedChannel(text(channel.get("key")));
        if (allowed == null) {
            return "결제 채널 키 불일치 또는 누락";
        }
        // 채널 유형은 모든 허용 채널에 공통이다. TEST 채널과 LIVE 채널을 동시에 여는 구성은
        // 기능이 아니라 사고다.
        if (!expectedChannelType.equals(normalizedText(channel.get("type")))) {
            return "결제 채널 유형 불일치 또는 누락";
        }
        if (!(payment.get("method") instanceof Map<?, ?>)) {
            return "결제수단 정보 누락";
        }
        // 포트원 표기의 해석은 PortOnePaymentMethodMapper 한 곳에만 둔다. 검증이 별도로
        // 파싱하면 실제 표기(`PaymentMethodEasyPay`)를 한쪽만 아는 상태가 되고, 그러면
        // 정상 결제가 전부 수동 검토로 떨어진다. 실제로 그렇게 깨져 있었다.
        PaymentMethod method = PortOnePaymentMethodMapper.from(payment);
        if (method.type() != allowed.type()) {
            return "결제수단 유형 불일치 또는 누락";
        }
        // provider는 PaymentMethod 생성자가 이미 대문자로 정규화한다. 카드처럼 사업자 구분이
        // 없는 수단은 매퍼도 튜플도 null이라 이 검사를 그대로 통과한다.
        if (!Objects.equals(allowed.provider(), method.provider())) {
            return allowed.provider() == null ? "결제수단 제공자 불일치" : "간편결제 제공자 불일치 또는 누락";
        }
        return null;
    }

    private AllowedChannel findAllowedChannel(String key) {
        if (key == null) {
            return null;
        }
        return allowedChannels.stream()
                .filter(channel -> channel.key().equals(key))
                .findFirst()
                .orElse(null);
    }

    private String findPaymentProvenanceFailure(Map<?, ?> payment, String expectedPaymentId, long expectedAmount) {
        if (!expectedPaymentId.equals(text(payment.get("id")))) {
            return "결제 ID 불일치 또는 누락";
        }
        if (!expectedStoreId.equals(text(payment.get("storeId")))) {
            return "상점 ID 불일치 또는 누락";
        }
        if (!"V2".equals(normalizedText(payment.get("version")))) {
            return "결제 API 버전 불일치 또는 누락";
        }
        if (!"KRW".equals(text(payment.get("currency")))) {
            return "결제 통화 불일치 또는 누락";
        }
        if (extractPaidTotal(payment) != expectedAmount) {
            return "결제 금액 불일치 또는 누락";
        }
        return null;
    }

    private static String text(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String normalizedText(Object value) {
        String text = text(value);
        return text == null ? null : text.trim().toUpperCase(Locale.ROOT);
    }

    private void publishReviewRequired(String orderId, String reason, String pgStatus) {
        operationalEvents.publish(new OperationalEvent(
                Category.PAYMENT,
                Level.ERROR,
                "결제 수동 확인 필요",
                "주문 상태를 변경하지 않고 보존했습니다. PortOne 대시보드와 관리자 주문 화면에서 확인하세요.",
                Map.of("주문 ID", orderId, "사유", reason, "PG 상태", pgStatus == null ? "누락" : pgStatus),
                Instant.now()));
    }

    /** 원장을 손에 쥐고 있을 때는 관측값까지 알림에 실어 보낸다. 알림만 보고 원인을 좁힐 수 있어야 한다. */
    private void publishReviewRequired(String orderId, String reason, String pgStatus, Map<?, ?> payment) {
        operationalEvents.publish(new OperationalEvent(
                Category.PAYMENT,
                Level.ERROR,
                "결제 수동 확인 필요",
                "주문 상태를 변경하지 않고 보존했습니다. PortOne 대시보드와 관리자 주문 화면에서 확인하세요.",
                Map.of(
                        "주문 ID",
                        orderId,
                        "사유",
                        reason,
                        "PG 상태",
                        pgStatus == null ? "누락" : pgStatus,
                        "PG 원장",
                        observedLedger(payment)),
                Instant.now()));
    }

    /**
     * 어긋난 검사 이름과 함께 <b>기대값·관측값을 나란히</b> 남긴다.
     *
     * <p>사유만으로는 원인을 못 찾는다. 실제로 {@code method.type} 표기 하나가 어긋나
     * 모든 실결제가 수동 검토로 떨어졌을 때, 로그에 "결제수단 유형 불일치"만 있어서
     * 포트원 원장을 따로 조회해야 비로소 원인이 드러났다. 그 왕복을 없앤다.
     *
     * <p>API secret은 여기 오지 않는다. 결제 조회 응답에 포함되지 않는 값이다.
     */
    private void logLedgerMismatch(String what, String orderId, String reason, long amount, Map<?, ?> payment) {
        log.error(
                "[PortOne] {} orderId={} reason={}{}  기대: {}{}  실제: {}",
                what,
                orderId,
                reason,
                System.lineSeparator(),
                expectedLedger(orderId, amount),
                System.lineSeparator(),
                observedLedger(payment));
    }

    private String expectedLedger(String orderId, long amount) {
        return "id=%s storeId=%s version=V2 currency=KRW amount=%d channel.type=%s 허용 채널: %s"
                .formatted(orderId, expectedStoreId, amount, expectedChannelType, allowedChannelSummary());
    }

    /**
     * 허용 채널을 <b>전부</b> 적는다. 채널이 둘 이상이면 "어느 채널 기준으로 어긋났는지"가
     * 사유 문구만으로는 드러나지 않는다.
     */
    private String allowedChannelSummary() {
        return allowedChannels.stream()
                .map(channel -> channel.key() + "=" + channel.label())
                .collect(Collectors.joining(", ", "[", "]"));
    }

    /** 정규화하지 않은 원문을 그대로 보여준다. 표기 차이가 원인일 때 정규화하면 그 단서가 사라진다. */
    private static String observedLedger(Map<?, ?> payment) {
        Map<?, ?> channel = payment.get("channel") instanceof Map<?, ?> found ? found : Map.of();
        Map<?, ?> method = payment.get("method") instanceof Map<?, ?> found ? found : Map.of();
        return "id=%s storeId=%s version=%s currency=%s amount=%d channel.key=%s channel.type=%s method.type=%s method.provider=%s status=%s"
                .formatted(
                        payment.get("id"),
                        payment.get("storeId"),
                        payment.get("version"),
                        payment.get("currency"),
                        extractPaidTotal(payment),
                        channel.get("key"),
                        channel.get("type"),
                        method.get("type"),
                        method.get("provider"),
                        payment.get("status"));
    }
}
