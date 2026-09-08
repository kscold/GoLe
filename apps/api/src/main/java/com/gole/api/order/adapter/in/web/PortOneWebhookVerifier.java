package com.gole.api.order.adapter.in.web;

import com.gole.api.common.exception.BadRequestException;
import io.portone.sdk.server.errors.WebhookVerificationException;
import io.portone.sdk.server.webhook.WebhookVerifier;
import kotlinx.serialization.SerializationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** PortOne Standard Webhooks 서명과 타임스탬프를 원문 본문 기준으로 검증한다. */
@Component
public class PortOneWebhookVerifier {

    private static final Logger log = LoggerFactory.getLogger(PortOneWebhookVerifier.class);

    private final WebhookVerifier verifier;

    public PortOneWebhookVerifier(@Value("${portone.webhook-secret:}") String webhookSecret) {
        this.verifier = webhookSecret == null || webhookSecret.isBlank() ? null : createVerifier(webhookSecret);
    }

    /** 검증기나 서명 비밀이 없으면 어떤 환경에서도 웹훅을 신뢰하지 않는다. */
    public void verify(String body, String messageId, String signature, String timestamp) {
        if (verifier == null) {
            throw new BadRequestException("INVALID_PAYMENT_WEBHOOK", "유효하지 않은 결제 웹훅입니다.");
        }
        // SDK의 verify는 Kotlin 비널 파라미터라 헤더가 하나라도 없으면 검증 예외가 아니라
        // NullPointerException을 던진다. 그대로 두면 서명 없는 요청이 400이 아니라 500으로
        // 끝나 PortOne이 재시도하고, 인증되지 않은 호출자도 장애 알림을 만들 수 있다.
        // 헤더 누락은 서명 불일치와 같은 "유효하지 않은 웹훅"이므로 SDK 호출 전에 끊는다.
        if (isBlank(messageId) || isBlank(signature) || isBlank(timestamp)) {
            log.warn(
                    "[PortOne webhook] signature headers missing: webhook-id={} webhook-signature={} webhook-timestamp={}",
                    presence(messageId),
                    presence(signature),
                    presence(timestamp));
            throw new BadRequestException("INVALID_PAYMENT_WEBHOOK", "유효하지 않은 결제 웹훅입니다.");
        }
        try {
            verifier.verify(body, messageId, signature, timestamp);
        } catch (WebhookVerificationException | SerializationException ex) {
            log.warn(
                    "[PortOne webhook] signature verification failed cause={}",
                    ex.getClass().getSimpleName());
            throw new BadRequestException("INVALID_PAYMENT_WEBHOOK", "유효하지 않은 결제 웹훅입니다.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** 헤더 값 자체(서명)는 로그에 남기지 않고 유무만 남긴다. */
    private static String presence(String value) {
        return isBlank(value) ? "missing" : "present";
    }

    private static WebhookVerifier createVerifier(String webhookSecret) {
        try {
            return new WebhookVerifier(webhookSecret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("PORTONE_WEBHOOK_SECRET must be a valid PortOne webhook secret", ex);
        }
    }
}
