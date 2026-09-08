package com.gole.api.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gole.api.admin.application.service.ExceptionQueueService;
import com.gole.api.common.operations.DiscordOperationsProperties;
import com.gole.api.operations.adapter.out.diagnostics.ExistingOperationsDiagnostics;
import com.gole.api.order.application.port.in.GetPaymentReadinessUseCase;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExistingOperationsDiagnosticsTest {
    @Test
    void invokesExistingReadOnlyUseCasesAndNeverReturnsWebhook() {
        var queue = mock(ExceptionQueueService.class);
        var payment = mock(GetPaymentReadinessUseCase.class);
        var discord = new DiscordOperationsProperties();
        discord.setOperationsWebhookUrl("https://discord.com/api/webhooks/123/SECRET");
        var diagnostics = new ExistingOperationsDiagnostics(queue, payment, discord);
        when(queue.list()).thenReturn(List.of());
        assertEquals("EXCEPTIONS_0", diagnostics.inspect("exception-queue"));
        verify(queue).list();
        when(payment.getPaymentReadiness())
                .thenReturn(new GetPaymentReadinessUseCase.Snapshot(
                        false,
                        false,
                        GetPaymentReadinessUseCase.State.DISABLED,
                        GetPaymentReadinessUseCase.ChannelType.UNKNOWN,
                        List.of(),
                        "KRW",
                        List.of()));
        assertEquals("PAYMENT_DISABLED", diagnostics.inspect("payment-readiness"));
        verify(payment).getPaymentReadiness();
        assertEquals("DISCORD_DISABLED_SENTRY_NOT_INSTRUMENTED", diagnostics.inspect("alert-readiness"));
        discord.setEnabled(true);
        assertFalse(diagnostics.inspect("alert-readiness").contains("SECRET"));
        assertThrows(IllegalArgumentException.class, () -> diagnostics.inspect("refund"));
    }
}
