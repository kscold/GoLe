package com.gole.api.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.gole.api.common.exception.*;
import com.gole.api.operations.application.port.out.OperationsDiagnostics;
import com.gole.api.operations.application.port.out.OperationsStore;
import com.gole.api.operations.application.service.OperationsService;
import com.gole.api.operations.domain.OperationRun;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OperationsServiceTest {
    final OperationsStore store = mock(OperationsStore.class);
    final OperationsDiagnostics diagnostics = mock(OperationsDiagnostics.class);
    final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);
    final OperationsService service = new OperationsService(store, diagnostics, clock);

    @Test
    void missingActorFailsBeforeStore() {
        assertThrows(ForbiddenException.class, () -> service.execute("exception-queue", "", "MANUAL_CHECK", null));
        verifyNoInteractions(store, diagnostics);
    }

    @Test
    void arbitraryJobsAndReasonsAreRejected() {
        assertThrows(
                BadRequestException.class, () -> service.execute("https://example.com", "admin", "MANUAL_CHECK", null));
        assertThrows(BadRequestException.class, () -> service.execute("exception-queue", "admin", "secret", null));
        verifyNoInteractions(store, diagnostics);
    }

    @Test
    void duplicateRunNeverExecutes() {
        when(store.acquire(anyString(), anyString())).thenReturn(false);
        assertThrows(ConflictException.class, () -> service.execute("exception-queue", "admin", "MANUAL_CHECK", null));
        verifyNoInteractions(diagnostics);
    }

    @Test
    void successIsAuditedBeforeExecutionAndAfterCompletion() {
        when(store.acquire(anyString(), anyString())).thenReturn(true);
        when(diagnostics.inspect("exception-queue")).thenReturn("EXCEPTIONS_3");
        var run = service.execute("exception-queue", "admin", "INCIDENT_REVIEW", null);
        assertEquals("SUCCEEDED", run.status());
        assertEquals("EXCEPTIONS_3", run.resultCode());
        var ordered = inOrder(store, diagnostics);
        ordered.verify(store).acquire(eq("exception-queue"), anyString());
        ordered.verify(store).save(argThat(value -> value.status().equals("RUNNING")));
        ordered.verify(diagnostics).inspect("exception-queue");
        ordered.verify(store).save(run);
        ordered.verify(store).release("exception-queue", run.id());
    }

    @Test
    void failedJobMasksAllExceptionSecretsAndReleasesLock() {
        when(store.acquire(anyString(), anyString())).thenReturn(true);
        when(diagnostics.inspect(anyString()))
                .thenThrow(new IllegalStateException("https://discord.com/api/webhooks/123/SECRET password=SECRET"));
        var run = service.execute("exception-queue", "admin", "MANUAL_CHECK", null);
        assertEquals("FAILED", run.status());
        assertEquals("DIAGNOSTIC_UNAVAILABLE", run.resultCode());
        var capture = ArgumentCaptor.forClass(OperationRun.class);
        verify(store, times(2)).save(capture.capture());
        assertFalse(capture.getAllValues().toString().contains("SECRET"));
        verify(store).release("exception-queue", run.id());
    }

    @Test
    void auditFailurePreventsExecution() {
        when(store.acquire(anyString(), anyString())).thenReturn(true);
        doThrow(new IllegalStateException("database down")).when(store).save(any());
        assertThrows(
                IllegalStateException.class, () -> service.execute("exception-queue", "admin", "MANUAL_CHECK", null));
        verifyNoInteractions(diagnostics);
        verify(store).release(anyString(), anyString());
    }

    @Test
    void completionAuditFailureRetainsLock() {
        when(store.acquire(anyString(), anyString())).thenReturn(true);
        doNothing()
                .doThrow(new IllegalStateException("database down"))
                .when(store)
                .save(any());
        assertThrows(
                IllegalStateException.class, () -> service.execute("exception-queue", "admin", "MANUAL_CHECK", null));
        verify(store, never()).release(anyString(), anyString());
    }

    @Test
    void retryMustReferenceSameFailedJob() {
        when(store.find("prior"))
                .thenReturn(Optional.of(new OperationRun(
                        "prior",
                        "exception-queue",
                        "admin",
                        "MANUAL_CHECK",
                        null,
                        "FAILED",
                        clock.instant(),
                        clock.instant(),
                        "DIAGNOSTIC_UNAVAILABLE")));
        assertThrows(
                BadRequestException.class,
                () -> service.execute("payment-readiness", "admin", "RETRY_FAILED", "prior"));
        when(store.acquire(anyString(), anyString())).thenReturn(true);
        assertEquals(
                "prior",
                service.execute("exception-queue", "admin", "RETRY_FAILED", "prior")
                        .retryOf());
    }
}
