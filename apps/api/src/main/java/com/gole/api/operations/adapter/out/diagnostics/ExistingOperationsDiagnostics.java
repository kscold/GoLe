package com.gole.api.operations.adapter.out.diagnostics;

import com.gole.api.admin.application.service.ExceptionQueueService;
import com.gole.api.common.operations.DiscordOperationsProperties;
import com.gole.api.operations.application.port.out.OperationsDiagnostics;
import com.gole.api.order.application.port.in.GetPaymentReadinessUseCase;
import org.springframework.stereotype.Component;

@Component
public class ExistingOperationsDiagnostics implements OperationsDiagnostics {
    private final ExceptionQueueService exceptions;
    private final GetPaymentReadinessUseCase payment;
    private final DiscordOperationsProperties discord;

    public ExistingOperationsDiagnostics(
            ExceptionQueueService exceptions, GetPaymentReadinessUseCase payment, DiscordOperationsProperties discord) {
        this.exceptions = exceptions;
        this.payment = payment;
        this.discord = discord;
    }

    public String inspect(String jobId) {
        return switch (jobId) {
            case "exception-queue" -> "EXCEPTIONS_" + exceptions.list().size();
            case "payment-readiness" -> "PAYMENT_"
                    + payment.getPaymentReadiness().state().name();
            case "alert-readiness" -> discord.isEnabled()
                    ? "DISCORD_CONFIGURED_SENTRY_NOT_INSTRUMENTED"
                    : "DISCORD_DISABLED_SENTRY_NOT_INSTRUMENTED";
            default -> throw new IllegalArgumentException("UNKNOWN_OPERATION");
        };
    }
}
