package com.gole.api.design;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gole.api.common.exception.*;
import com.gole.api.design.application.port.out.DesignRepositoryPort;
import com.gole.api.design.application.service.DesignService;
import com.gole.api.design.domain.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class DesignServiceTest {
    final DesignRepositoryPort repository = mock(DesignRepositoryPort.class);
    final DesignService service = new DesignService(repository);

    @Test
    void validatesAllowlistAndRejectsCssInjection() {
        assertThat(DesignSchema.validate(DesignSchema.defaults())).hasSize(23);
        for (String bad : List.of("red", "#fff", "url(https://x)", "#ffffff;display:none")) {
            var tokens = new HashMap<>(DesignSchema.defaults());
            tokens.put("--color-brand-600", bad);
            assertThatThrownBy(() -> service.publish(0, tokens, "reason", "admin"))
                    .isInstanceOf(BadRequestException.class);
        }
        var extra = new HashMap<>(DesignSchema.defaults());
        extra.put("--evil", "1px");
        assertThatThrownBy(() -> DesignSchema.validate(extra)).isInstanceOf(BadRequestException.class);
        var large = new HashMap<>(DesignSchema.defaults());
        large.put("--design-font-size", "999px");
        assertThatThrownBy(() -> DesignSchema.validate(large)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void publicationIncludesAtomicAuditAndChecksRevision() {
        when(repository.current()).thenReturn(DesignRevision.initial());
        var next = service.publish(0, DesignSchema.defaults(), " new colors ", "admin-1");
        assertThat(next.revision()).isEqualTo(1);
        assertThat(next.actorId()).isEqualTo("admin-1");
        assertThat(next.reason()).isEqualTo("new colors");
        verify(repository).append(next);
        assertThatThrownBy(() -> service.publish(2, DesignSchema.defaults(), "reason", "admin"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void requiresActorAndReason() {
        assertThatThrownBy(() -> service.publish(0, DesignSchema.defaults(), "reason", ""))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service.publish(0, DesignSchema.defaults(), " ", "admin"))
                .isInstanceOf(BadRequestException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void restoreAndResetAppendWithoutRewritingHistory() {
        var old =
                new DesignRevision(1, DesignSchema.defaults(), "old-admin", "old", "PUBLISH", java.time.Instant.EPOCH);
        when(repository.current()).thenReturn(old);
        when(repository.find(1)).thenReturn(Optional.of(old));
        var restored = service.restore(1, 1, "rollback", "admin");
        assertThat(restored.action()).isEqualTo("RESTORE:1");
        assertThat(restored.revision()).isEqualTo(2);
        verify(repository).append(restored);
        assertThat(service.restore(1, 0, "reset", "admin").action()).isEqualTo("RESET");
    }
}
