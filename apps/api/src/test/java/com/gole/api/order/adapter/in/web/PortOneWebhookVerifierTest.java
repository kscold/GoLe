package com.gole.api.order.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gole.api.common.exception.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PortOneWebhookVerifierTest {

    private static final byte[] SECRET = "gole-portone-webhook-test-secret".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED_SECRET = "whsec_" + Base64.getEncoder().encodeToString(SECRET);
    private static final String BODY = "{\"type\":\"Transaction.Paid\",\"timestamp\":\"2026-08-09T00:00:00Z\","
            + "\"data\":{\"paymentId\":\"order-1\",\"storeId\":\"store-1\",\"transactionId\":\"tx-1\"}}";

    @Test
    void acceptsValidStandardWebhookSignature() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String messageId = "message-1";
        PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(ENCODED_SECRET);

        assertThatCode(() -> verifier.verify(
                        BODY, messageId, signature(messageId, timestamp, BODY), Long.toString(timestamp)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsBodyTamperingAndStaleReplay() throws Exception {
        long now = Instant.now().getEpochSecond();
        String messageId = "message-2";
        PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(ENCODED_SECRET);

        assertThatThrownBy(() ->
                        verifier.verify(BODY + " ", messageId, signature(messageId, now, BODY), Long.toString(now)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("유효하지 않은");

        long staleTimestamp = now - 301;
        assertThatThrownBy(() -> verifier.verify(
                        BODY, messageId, signature(messageId, staleTimestamp, BODY), Long.toString(staleTimestamp)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("유효하지 않은");
    }

    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"webhook-id", "webhook-signature", "webhook-timestamp"})
    void rejectsMissingSignatureHeaderAsInvalidWebhookNotServerError(String missingHeader) throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String messageId = "message-3";
        String signature = signature(messageId, timestamp, BODY);
        PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(ENCODED_SECRET);

        for (String absent : new String[] {null, "", "  "}) {
            String id = "webhook-id".equals(missingHeader) ? absent : messageId;
            String sig = "webhook-signature".equals(missingHeader) ? absent : signature;
            String ts = "webhook-timestamp".equals(missingHeader) ? absent : Long.toString(timestamp);

            // SDK로 넘어가면 NullPointerException(500)이 된다. 그 전에 400 경계로 끝나야 한다.
            assertThatThrownBy(() -> verifier.verify(BODY, id, sig, ts))
                    .isInstanceOf(BadRequestException.class)
                    .isNotInstanceOf(NullPointerException.class)
                    .hasMessageContaining("유효하지 않은");
        }
    }

    @Test
    void missingHeaderGuardDoesNotWeakenValidPath() throws Exception {
        // 헤더가 모두 있으면 가드를 통과해 실제 서명 검증까지 간다 — 올바른 서명은 통과, 틀린 서명은 거절.
        long timestamp = Instant.now().getEpochSecond();
        String messageId = "message-4";
        PortOneWebhookVerifier verifier = new PortOneWebhookVerifier(ENCODED_SECRET);

        assertThatCode(() -> verifier.verify(
                        BODY, messageId, signature(messageId, timestamp, BODY), Long.toString(timestamp)))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> verifier.verify(BODY, messageId, "v1,bm90LWEtc2lnbmF0dXJl", Long.toString(timestamp)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void missingSecretAlwaysFailsClosed() {
        PortOneWebhookVerifier verifier = new PortOneWebhookVerifier("");

        assertThatThrownBy(() -> verifier.verify(BODY, null, null, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("유효하지 않은");
    }

    private static String signature(String messageId, long timestamp, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        byte[] signature = mac.doFinal((messageId + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
        return "v1," + Base64.getEncoder().encodeToString(signature);
    }
}
