package com.gole.api.order.adapter.out.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.gole.api.common.operations.OperationalEventPublisher;
import com.gole.api.order.application.port.out.PaymentGatewayPort.PaymentVerification;
import com.gole.api.order.application.port.out.PaymentGatewayPort.PaymentVerificationResult;
import com.gole.api.order.application.port.out.PaymentGatewayPort.RefundResult;
import com.gole.api.order.application.port.out.PaymentGatewayUnavailableException;
import com.gole.api.order.application.port.out.PaymentReviewRequiredException;
import com.gole.api.order.domain.model.PaymentEvidenceKind;
import com.gole.api.order.domain.model.PaymentMethod;
import com.gole.api.order.domain.model.PaymentMethodType;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PortOnePaymentGatewayAdapterTest {

    private final AtomicReference<String> responseBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> cancelResponseBody =
            new AtomicReference<>("{\"cancellation\":{\"status\":\"SUCCEEDED\",\"totalAmount\":15000}}");
    private final AtomicInteger cancelResponseStatus = new AtomicInteger(200);
    private final AtomicInteger cancelRequests = new AtomicInteger();
    private final AtomicInteger preRegisterRequests = new AtomicInteger();
    private static final long READ_TIMEOUT_MS = 500;

    /** 응답을 이 시간만큼 지연한다. 읽기 타임아웃 테스트용. */
    private final AtomicInteger responseDelayMs = new AtomicInteger();

    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        responseStatus.set(200);
        responseBody.set("{}");
        cancelResponseStatus.set(200);
        cancelResponseBody.set("{\"cancellation\":{\"status\":\"SUCCEEDED\",\"totalAmount\":15000}}");
        cancelRequests.set(0);
        preRegisterRequests.set(0);
        requestBody.set(null);
        responseDelayMs.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payments", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestPath.set(exchange.getRequestURI().getPath());
            boolean post = "POST".equals(exchange.getRequestMethod());
            boolean cancellation = post && exchange.getRequestURI().getPath().endsWith("/cancel");
            boolean preRegistration = post && exchange.getRequestURI().getPath().endsWith("/pre-register");
            if (post) {
                if (cancellation) {
                    cancelRequests.incrementAndGet();
                }
                if (preRegistration) {
                    preRegisterRequests.incrementAndGet();
                }
                requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            if (responseDelayMs.get() > 0) {
                try {
                    Thread.sleep(responseDelayMs.get());
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = (cancellation ? cancelResponseBody.get() : post ? "{}" : responseBody.get())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(cancellation ? cancelResponseStatus.get() : responseStatus.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("카카오페이 테스트 채널의 PAID 원장만 승인한다")
    void approvesPaidKakaoPayFromConfiguredTestChannel() {
        responseBody.set(validPaidResponse("order-1"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = adapter(events).verifyPayment("order-1", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.PAID);
        assertThat(requestPath.get()).isEqualTo("/payments/order-1");
        assertThat(authorization.get()).isEqualTo("PortOne api-secret");
    }

    /**
     * 승인 원장에서만 얻을 수 있는 결제수단을 검증 결과에 실어 돌려준다.
     * 이미 검증이 읽은 값이므로 추가 조회 없이 따라와야 한다.
     */
    @Test
    @DisplayName("승인된 결제의 결제수단을 검증 결과에 함께 담아 돌려준다")
    void reportsPaymentMethodOfApprovedPayment() {
        responseBody.set(validPaidResponse("order-1"));

        PaymentVerification result =
                adapter(mock(OperationalEventPublisher.class)).verifyPayment("order-1", 15_000);

        assertThat(result.method()).isEqualTo(new PaymentMethod(PaymentMethodType.EASY_PAY, "KAKAOPAY"));
        assertThat(result.evidenceKind()).isEqualTo(PaymentEvidenceKind.TEST);
    }

    @Test
    @DisplayName("LIVE 채널 결제는 주문에 실제 금전 결제 증빙으로 고정한다")
    void recordsLivePaymentEvidenceAtVerificationTime() {
        responseBody.set(validPaidResponse("order-live").replace("\"TEST\"", "\"LIVE\""));

        PaymentVerification result =
                adapter(mock(OperationalEventPublisher.class), "", "LIVE").verifyPayment("order-live", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.PAID);
        assertThat(result.evidenceKind()).isEqualTo(PaymentEvidenceKind.LIVE);
    }

    /**
     * 실제 카카오페이 결제에서 받은 원문 그대로다. {@code method.type}이 discriminator
     * ({@code PaymentMethodEasyPay})로 온다는 사실을 픽스처와 별개로 한 번 더 못박는다.
     *
     * <p>검증 게이트가 {@code "EASY_PAY"} 문자열을 직접 비교하던 동안 실결제는 100%
     * 수동 검토로 떨어졌는데, 픽스처가 같은 잘못된 표기를 쓰고 있어 테스트는 초록이었다.
     * 픽스처를 되돌리는 변경이 있어도 이 테스트는 남아서 걸러낸다.
     */
    @Test
    @DisplayName("포트원이 실제로 주는 PaymentMethodEasyPay 표기를 승인으로 인정한다")
    void acceptsRealPortOneEasyPayDiscriminator() {
        responseBody.set(
                """
                {"status":"PAID","id":"order-1","storeId":"store-1","version":"V2","currency":"KRW",
                "amount":{"total":15000},
                "channel":{"type":"TEST","key":"channel-key-1","pgProvider":"KAKAOPAY"},
                "method":{"type":"PaymentMethodEasyPay","provider":"KAKAOPAY",
                "easyPayMethod":{"type":"PaymentMethodEasyPayMethodCharge"}}}
                """);
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = adapter(events).verifyPayment("order-1", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.PAID);
        assertThat(result.method()).isEqualTo(new PaymentMethod(PaymentMethodType.EASY_PAY, "KAKAOPAY"));
        // 승인된 결제는 수동 검토 이벤트를 만들지 않는다.
        verifyNoInteractions(events);
    }

    @Test
    @DisplayName("카드 채널의 PAID 카드 원장을 승인하고 결제수단을 카드로 기록한다")
    void approvesPaidCardFromConfiguredCardChannel() {
        responseBody.set(validPaidCardResponse("order-card"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = cardEnabledAdapter(events).verifyPayment("order-card", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.PAID);
        // 카드는 간편결제 사업자 구분이 없다. provider를 채우면 결제수단 집계가 오염된다.
        assertThat(result.method()).isEqualTo(PaymentMethod.of(PaymentMethodType.CARD));
        verifyNoInteractions(events);
    }

    /**
     * 이 스펙에서 진짜 위험한 지점이다. 허용 채널과 허용 수단을 <b>따로</b> 검사하면
     * 두 조건 모두 만족하는 이 원장이 통과한다 — 우리가 계약하지 않은 경로다.
     */
    @Test
    @DisplayName("카카오페이 채널로 들어온 카드 결제는 승인하지 않는다")
    void rejectsCardPaymentThatCameThroughTheKakaoPayChannel() {
        responseBody.set(validPaidCardResponse("order-card").replace("card-channel-1", "channel-key-1"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = cardEnabledAdapter(events).verifyPayment("order-card", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.REVIEW_REQUIRED);
        verify(events).publish(any());
    }

    @Test
    @DisplayName("카드 채널로 들어온 간편결제는 승인하지 않는다")
    void rejectsEasyPayPaymentThatCameThroughTheCardChannel() {
        responseBody.set(validPaidResponse("order-1").replace("channel-key-1", "card-channel-1"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = cardEnabledAdapter(events).verifyPayment("order-1", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.REVIEW_REQUIRED);
        verify(events).publish(any());
    }

    /** 카드 채널을 설정하지 않은 환경에서는 카드 원장이 어느 허용 채널과도 맞지 않아야 한다. */
    @Test
    @DisplayName("카드 채널이 설정되지 않으면 카드 결제를 승인하지 않는다")
    void rejectsCardPaymentWhenCardChannelIsNotConfigured() {
        responseBody.set(validPaidCardResponse("order-card"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = adapter(events).verifyPayment("order-card", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.REVIEW_REQUIRED);
        verify(events).publish(any());
    }

    @Test
    @DisplayName("검증된 카드 결제도 같은 전액 환불 경로를 탄다")
    void refundsValidatedCardPayment() {
        responseBody.set(validPaidCardResponse("order-card-refund"));

        RefundResult result =
                cardEnabledAdapter(mock(OperationalEventPublisher.class)).refund("order-card-refund", 15_000);

        assertThat(result).isEqualTo(RefundResult.SUCCEEDED);
        assertThat(cancelRequests).hasValue(1);
        assertThat(requestPath.get()).isEqualTo("/payments/order-card-refund/cancel");
    }

    /** 승인되지 않은 결제의 결제수단은 사실이 아니다. 기록할 것이 없으므로 비어 있어야 한다. */
    @Test
    @DisplayName("승인되지 않은 결제는 결제수단을 싣지 않는다")
    void omitsPaymentMethodWhenPaymentIsNotApproved() {
        responseBody.set(basePaymentResponse("FAILED", "order-failed"));

        PaymentVerification result =
                adapter(mock(OperationalEventPublisher.class)).verifyPayment("order-failed", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.FAILED);
        assertThat(result.method()).isNull();
    }

    @Test
    @DisplayName("결제창 호출 전 서버가 상점·KRW·주문 금액을 PortOne에 사전 등록한다")
    void preRegistersTrustedOrderAmount() {
        adapter(mock(OperationalEventPublisher.class)).preparePayment("order-prepared", 15_000);

        assertThat(preRegisterRequests).hasValue(1);
        assertThat(cancelRequests).hasValue(0);
        assertThat(requestPath.get()).isEqualTo("/payments/order-prepared/pre-register");
        assertThat(authorization.get()).isEqualTo("PortOne api-secret");
        assertThat(requestBody.get())
                .contains("\"storeId\":\"store-1\"")
                .contains("\"totalAmount\":15000")
                .contains("\"currency\":\"KRW\"");
    }

    @Test
    @DisplayName("결제 사전 등록 장애는 주문을 진행하지 않도록 재시도 가능 오류로 반환한다")
    void failsClosedWhenPreRegistrationIsUnavailable() {
        responseStatus.set(503);

        assertThatThrownBy(() ->
                        adapter(mock(OperationalEventPublisher.class)).preparePayment("order-unavailable", 15_000))
                .isInstanceOf(PaymentGatewayUnavailableException.class);

        assertThat(preRegisterRequests).hasValue(1);
        assertThat(cancelRequests).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPaidResponses")
    @DisplayName("PAID 응답의 결제 식별 정보가 누락되거나 다르면 수동 검토로 보낸다")
    void rejectsPaidResponseThatDoesNotMatchConfiguredKakaoPay(String reason, String response) {
        responseBody.set(response);
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = adapter(events).verifyPayment("order-1", 15_000);

        assertThat(result.result()).as(reason).isEqualTo(PaymentVerificationResult.REVIEW_REQUIRED);
        verify(events).publish(any());
    }

    @ParameterizedTest
    @MethodSource("pendingStatuses")
    @DisplayName("READY와 PAY_PENDING 원장은 결제 대기로 보존한다")
    void preservesNonFinalPaymentStatuses(String status) {
        responseBody.set(basePaymentResponse(status, "order-1"));

        PaymentVerification result =
                adapter(mock(OperationalEventPublisher.class)).verifyPayment("order-1", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.PENDING);
    }

    @Test
    @DisplayName("다른 결제 ID의 PAY_PENDING 응답도 정상 대기로 오인하지 않는다")
    void rejectsPendingPaymentWithDifferentPaymentId() {
        responseBody.set(basePaymentResponse("PAY_PENDING", "order-other"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = adapter(events).verifyPayment("order-1", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.REVIEW_REQUIRED);
        verify(events).publish(any());
    }

    @Test
    @DisplayName("결제수단 정보가 없는 정상 FAILED 원장도 기본 provenance 확인 후 실패 확정한다")
    void acceptsFailedPaymentOnlyAfterBaseProvenanceValidation() {
        responseBody.set(basePaymentResponse("FAILED", "order-failed"));

        PaymentVerification result =
                adapter(mock(OperationalEventPublisher.class)).verifyPayment("order-failed", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.FAILED);
    }

    @Test
    @DisplayName("다른 상점의 FAILED 응답은 예약을 풀 수 있는 실패 상태로 승인하지 않는다")
    void rejectsFailedPaymentFromDifferentStore() {
        responseBody.set(basePaymentResponse("FAILED", "order-failed").replace("store-1", "store-other"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        PaymentVerification result = adapter(events).verifyPayment("order-failed", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.REVIEW_REQUIRED);
        verify(events).publish(any());
    }

    @Test
    @DisplayName("결제 조회의 정확한 404만 미생성 결제 건으로 구분한다")
    void mapsPaymentNotFoundToNotFoundResult() {
        responseStatus.set(404);
        responseBody.set("{\"type\":\"PAYMENT_NOT_FOUND\"}");

        PaymentVerification result =
                adapter(mock(OperationalEventPublisher.class)).verifyPayment("order-never-opened", 15_000);

        assertThat(result.result()).isEqualTo(PaymentVerificationResult.NOT_FOUND);
    }

    @Test
    @DisplayName("PortOne이 응답하지 않으면 무한 대기하지 않고 읽기 타임아웃 안에 재시도 가능 장애로 끝난다")
    void timesOutInsteadOfHangingWhenPortOneStalls() {
        responseDelayMs.set((int) READ_TIMEOUT_MS * 6);
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);
        PortOnePaymentGatewayAdapter adapter = adapter(events);

        long started = System.nanoTime();
        assertThatThrownBy(() -> adapter.verifyPayment("order-1", 15_000))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // 지연(3초)보다 훨씬 앞서 끝나야 타임아웃이 실제로 걸린 것이다.
        assertThat(elapsedMs).isLessThan(READ_TIMEOUT_MS * 4);
        // 조회 실패는 결제 거절이 아니므로 수동 검토 알림도 보내지 않는다.
        verifyNoInteractions(events);

        assertThatThrownBy(() -> adapter.refund("order-1", 15_000))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
        assertThat(cancelRequests.get()).isZero(); // 조회에서 멈췄으니 취소 요청은 나가지 않는다
    }

    @Test
    @DisplayName("타임아웃 설정이 0 이하이면 무한 대기를 허용하지 않고 기동을 거부한다")
    void rejectsNonPositiveTimeouts() {
        assertThatThrownBy(() -> new PortOnePaymentGatewayAdapter(
                        "http://127.0.0.1:1",
                        "api-secret",
                        "store-1",
                        "channel-key-1",
                        "",
                        "TEST",
                        Duration.ZERO,
                        Duration.ofSeconds(10),
                        mock(OperationalEventPublisher.class)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("portone.connect-timeout");
    }

    @ParameterizedTest
    @MethodSource("transientOrInvalidHttpStatuses")
    @DisplayName("404 이외의 PortOne 4xx/5xx는 매물 선점을 풀지 않는 재시도 가능 장애다")
    void keepsNon404HttpErrorsRetryable(int status) {
        responseStatus.set(status);
        responseBody.set("{\"message\":\"temporary error\"}");

        assertThatThrownBy(() -> adapter(mock(OperationalEventPublisher.class)).verifyPayment("order-1", 15_000))
                .isInstanceOf(PaymentGatewayUnavailableException.class);
    }

    @Test
    @DisplayName("검증된 카카오페이 결제만 PortOne 전액 환불 API로 전달한다")
    void refundsOnlyValidatedKakaoPayPayment() {
        responseBody.set(validPaidResponse("order-refund"));

        RefundResult result = adapter(mock(OperationalEventPublisher.class)).refund("order-refund", 15_000);

        assertThat(result).isEqualTo(RefundResult.SUCCEEDED);
        assertThat(cancelRequests).hasValue(1);
        assertThat(requestPath.get()).isEqualTo("/payments/order-refund/cancel");
        assertThat(requestBody.get())
                .contains("\"storeId\":\"store-1\"")
                .contains("\"reason\":\"주문 환불\"")
                .contains("\"requester\":\"CUSTOMER\"")
                .contains("\"amount\":15000")
                .contains("\"currentCancellableAmount\":15000");
    }

    @Test
    @DisplayName("다른 상점 결제는 자동 환불하지 않고 운영 검토로 보존한다")
    void rejectsRefundForPaymentFromDifferentStore() {
        responseBody.set(validPaidResponse("order-foreign").replace("store-1", "store-other"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        assertThatThrownBy(() -> adapter(events).refund("order-foreign", 15_000))
                .isInstanceOf(PaymentReviewRequiredException.class);

        assertThat(cancelRequests).hasValue(0);
        verify(events).publish(any());
    }

    @Test
    @DisplayName("부분취소 등 예상하지 않은 상태는 추가 자동 환불하지 않는다")
    void rejectsAutomaticRefundForPartiallyCancelledPayment() {
        responseBody.set(validPaidResponse("order-partial").replace("\"PAID\"", "\"PARTIAL_CANCELLED\""));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        assertThatThrownBy(() -> adapter(events).refund("order-partial", 15_000))
                .isInstanceOf(PaymentReviewRequiredException.class);

        assertThat(cancelRequests).hasValue(0);
        verify(events).publish(any());
    }

    @Test
    @DisplayName("동일 상점의 전액 취소 원장만 환불 완료로 확정한다")
    void confirmsOnlyValidatedFullyCancelledPayment() {
        responseBody.set(validCancelledResponse("order-cancelled"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        assertThat(adapter(events).isFullyRefunded("order-cancelled", 15_000)).isTrue();
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("이미 취소된 결제도 총 취소 금액을 검증한 뒤 멱등 성공 처리한다")
    void treatsOnlyFullyCancelledExistingPaymentAsIdempotentRefund() {
        responseBody.set(validCancelledResponse("order-cancelled"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        RefundResult result = adapter(events).refund("order-cancelled", 15_000);

        assertThat(result).isEqualTo(RefundResult.SUCCEEDED);
        assertThat(cancelRequests).hasValue(0);
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("이미 접수된 전액 환불은 취소 API를 중복 호출하지 않고 대기 처리한다")
    void treatsRequestedFullCancellationAsIdempotentRefund() {
        responseBody.set(validPaidResponse("order-requested")
                .replace(
                        "\"amount\":{\"total\":15000},",
                        "\"amount\":{\"total\":15000},"
                                + "\"cancellations\":[{\"status\":\"REQUESTED\",\"totalAmount\":15000}],"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        RefundResult result = adapter(events).refund("order-requested", 15_000);

        assertThat(result).isEqualTo(RefundResult.REQUESTED);
        assertThat(cancelRequests).hasValue(0);
        verify(events, never()).publish(any());
    }

    @Test
    @DisplayName("환불 성공 응답 금액이 주문 금액과 다르면 완료 처리하지 않는다")
    void rejectsCancellationResponseWithDifferentAmount() {
        responseBody.set(validPaidResponse("order-refund"));
        cancelResponseBody.set("{\"cancellation\":{\"status\":\"SUCCEEDED\",\"totalAmount\":14000}}");
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        assertThatThrownBy(() -> adapter(events).refund("order-refund", 15_000))
                .isInstanceOf(PaymentReviewRequiredException.class);

        verify(events).publish(any());
    }

    @Test
    @DisplayName("PortOne이 FAILED 환불 내역을 반환하면 일시 장애가 아닌 운영 검토로 분리한다")
    void mapsFailedCancellationToManualReview() {
        responseBody.set(validPaidResponse("order-refund"));
        cancelResponseBody.set("{\"cancellation\":{\"status\":\"FAILED\",\"totalAmount\":15000}}");
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        assertThatThrownBy(() -> adapter(events).refund("order-refund", 15_000))
                .isInstanceOf(PaymentReviewRequiredException.class);

        verify(events).publish(any());
    }

    @Test
    @DisplayName("CANCELLED 원장의 총 취소 금액이 주문 금액과 다르면 환불 완료로 확정하지 않는다")
    void rejectsCancelledPaymentWithDifferentCancelledAmount() {
        responseBody.set(
                validCancelledResponse("order-cancelled").replace("\"cancelled\":15000", "\"cancelled\":14000"));
        OperationalEventPublisher events = mock(OperationalEventPublisher.class);

        assertThatThrownBy(() -> adapter(events).isFullyRefunded("order-cancelled", 15_000))
                .isInstanceOf(PaymentReviewRequiredException.class);

        verify(events).publish(any());
    }

    /** 카카오페이만 연 기본 구성. 카드 채널 키가 없으면 카드는 어느 채널로도 승인되지 않는다. */
    private PortOnePaymentGatewayAdapter adapter(OperationalEventPublisher events) {
        return adapter(events, "");
    }

    /** 카드까지 연 구성. 카드가 열려도 카카오페이 쪽 검증이 느슨해지면 안 된다. */
    private PortOnePaymentGatewayAdapter cardEnabledAdapter(OperationalEventPublisher events) {
        return adapter(events, "card-channel-1");
    }

    private PortOnePaymentGatewayAdapter adapter(OperationalEventPublisher events, String cardChannelKey) {
        return adapter(events, cardChannelKey, "TEST");
    }

    private PortOnePaymentGatewayAdapter adapter(
            OperationalEventPublisher events, String cardChannelKey, String channelType) {
        return new PortOnePaymentGatewayAdapter(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "api-secret",
                "store-1",
                "channel-key-1",
                cardChannelKey,
                channelType,
                Duration.ofSeconds(3),
                Duration.ofMillis(READ_TIMEOUT_MS),
                events);
    }

    private static Stream<Arguments> invalidPaidResponses() {
        return Stream.of(
                Arguments.of("결제 ID 누락", validPaidResponse("order-1").replace("\"id\":\"order-1\",", "")),
                Arguments.of("결제 ID 불일치", validPaidResponse("order-other")),
                Arguments.of("상점 누락", validPaidResponse("order-1").replace("\"storeId\":\"store-1\",", "")),
                Arguments.of("상점 불일치", validPaidResponse("order-1").replace("store-1", "store-other")),
                Arguments.of("V2 아님", validPaidResponse("order-1").replace("\"V2\"", "\"V1\"")),
                Arguments.of("KRW 아님", validPaidResponse("order-1").replace("\"KRW\"", "\"USD\"")),
                Arguments.of(
                        "채널 누락",
                        validPaidResponse("order-1")
                                .replace("\"channel\":{\"key\":\"channel-key-1\",\"type\":\"TEST\"},", "")),
                Arguments.of("채널 키 불일치", validPaidResponse("order-1").replace("channel-key-1", "channel-other")),
                Arguments.of("채널 유형 불일치", validPaidResponse("order-1").replace("\"TEST\"", "\"LIVE\"")),
                Arguments.of(
                        "결제수단 누락",
                        """
                        {"status":"PAID","id":"order-1","storeId":"store-1","version":"V2",
                        "currency":"KRW","amount":{"total":15000},
                        "channel":{"key":"channel-key-1","type":"TEST"}}
                        """),
                Arguments.of(
                        "간편결제 아님",
                        // easyPayMethod 쪽 discriminator까지 같이 바뀌지 않도록 method.type만 정확히 지목한다.
                        validPaidResponse("order-1")
                                .replace("\"type\":\"PaymentMethodEasyPay\",", "\"type\":\"PaymentMethodCard\",")),
                Arguments.of("카카오페이 아님", validPaidResponse("order-1").replace("KAKAOPAY", "NAVERPAY")),
                Arguments.of("금액 누락", validPaidResponse("order-1").replace("\"amount\":{\"total\":15000},", "")),
                Arguments.of("금액 불일치", validPaidResponse("order-1").replace("15000", "16000")));
    }

    private static Stream<String> pendingStatuses() {
        return Stream.of("READY", "PAY_PENDING", "PENDING");
    }

    private static Stream<Integer> transientOrInvalidHttpStatuses() {
        return Stream.of(400, 401, 429, 500, 503);
    }

    /**
     * 실제 포트원 V2 {@code GET /payments/{id}} 응답 형태다. {@code method.type}은
     * {@code "EASY_PAY"}가 아니라 discriminator인 {@code "PaymentMethodEasyPay"}로 온다.
     *
     * <p>여기에 오지 않는 표기를 넣으면 통과하지 못할 코드가 통과한다. 실제로 이 픽스처가
     * {@code "EASY_PAY"}였던 동안 검증 게이트는 모든 실결제를 수동 검토로 떨어뜨렸고,
     * 테스트는 계속 초록이었다.
     */
    private static String validPaidResponse(String paymentId) {
        return """
                {"status":"PAID","id":"%s","storeId":"store-1","version":"V2","currency":"KRW",
                "amount":{"total":15000},"channel":{"key":"channel-key-1","type":"TEST"},
                "method":{"type":"PaymentMethodEasyPay","provider":"KAKAOPAY",
                "easyPayMethod":{"type":"PaymentMethodEasyPayMethodCharge"}}}
                """
                .formatted(paymentId);
    }

    /**
     * 카드 채널의 PAID 원장. 포트원은 카드 결제수단을 discriminator {@code PaymentMethodCard}로
     * 주며 간편결제와 달리 {@code provider}가 없다.
     */
    private static String validPaidCardResponse(String paymentId) {
        return """
                {"status":"PAID","id":"%s","storeId":"store-1","version":"V2","currency":"KRW",
                "amount":{"total":15000},"channel":{"key":"card-channel-1","type":"TEST"},
                "method":{"type":"PaymentMethodCard",
                "card":{"publisher":"신한카드","issuer":"신한카드","brand":"LOCAL"}}}
                """
                .formatted(paymentId);
    }

    private static String basePaymentResponse(String status, String paymentId) {
        return """
                {"status":"%s","id":"%s","storeId":"store-1","version":"V2","currency":"KRW",
                "amount":{"total":15000}}
                """
                .formatted(status, paymentId);
    }

    private static String validCancelledResponse(String paymentId) {
        return validPaidResponse(paymentId)
                .replace("\"PAID\"", "\"CANCELLED\"")
                .replace("\"total\":15000", "\"total\":15000,\"cancelled\":15000");
    }
}
