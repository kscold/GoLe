package com.gole.api.account.config;

import jakarta.mail.MessagingException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** 이메일 인증을 켠 배포가 실제 SMTP 연결·인증에 실패한 채 공개되는 것을 기동 단계에서 차단한다. */
@Component
@ConditionalOnProperty(name = "gole.verification.email.enabled", havingValue = "true")
public class SmtpConnectionConfigurationGuard implements ApplicationRunner {

    private final SmtpConnectionVerifier verifier;

    // 생성자가 둘이라 Spring이 주입 대상을 고르지 못한다. 테스트용 생성자와 구분해 명시한다.
    @Autowired
    public SmtpConnectionConfigurationGuard(JavaMailSenderImpl mailSender) {
        this(mailSender::testConnection);
    }

    SmtpConnectionConfigurationGuard(SmtpConnectionVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            verifier.verify();
        } catch (MessagingException exception) {
            // 호스트·계정 등 상세 메시지에는 제공자 정보가 섞일 수 있으므로 외부로 되비추지 않는다.
            throw new IllegalStateException("Verification email SMTP connection failed", exception);
        }
    }

    @FunctionalInterface
    interface SmtpConnectionVerifier {
        void verify() throws MessagingException;
    }
}
