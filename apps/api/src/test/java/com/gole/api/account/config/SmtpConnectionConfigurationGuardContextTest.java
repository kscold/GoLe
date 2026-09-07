package com.gole.api.account.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * {@link SmtpConnectionConfigurationGuardTest} calls the package-private test constructor
 * directly, so it never exercises Spring's own constructor selection and cannot catch a
 * dependency-injection defect on the production constructor. This test boots a real (if
 * minimal) application context instead, the same way Spring Boot would at startup.
 */
class SmtpConnectionConfigurationGuardContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SmtpConnectionConfigurationGuard.class)
            .withBean(JavaMailSenderImpl.class, JavaMailSenderImpl::new);

    @Test
    void disabledByDefaultDoesNotRegisterGuard() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(SmtpConnectionConfigurationGuard.class));
    }

    @Test
    void enabledFlagBootsContextAndRegistersGuardWithoutInvokingTheVerifier() {
        // gole.verification.email.enabled=true is the only condition that registers this
        // @Component, so a constructor-injection defect only surfaces once it is flipped on.
        // ApplicationContextRunner never calls SpringApplication.run(), so ApplicationRunner
        // beans are never invoked here; JavaMailSenderImpl gets no host, so a real connection
        // attempt would fail immediately rather than hang, and this still passes, confirming
        // the verifier's run() never fires just from refreshing the context.
        contextRunner.withPropertyValues("gole.verification.email.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SmtpConnectionConfigurationGuard.class);
        });
    }
}
