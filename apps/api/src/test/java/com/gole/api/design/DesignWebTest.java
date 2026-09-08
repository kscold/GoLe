package com.gole.api.design;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gole.api.account.adapter.in.web.SessionCookie;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase.CurrentSession;
import com.gole.api.account.domain.model.Role;
import com.gole.api.admin.adapter.in.web.AdminAuthInterceptor;
import com.gole.api.common.web.GlobalExceptionHandler;
import com.gole.api.design.adapter.in.web.DesignController;
import com.gole.api.design.application.port.in.ManageDesignUseCase;
import com.gole.api.design.domain.model.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DesignWebTest {
    final ManageDesignUseCase service = mock(ManageDesignUseCase.class);
    final GetCurrentSessionUseCase sessions = mock(GetCurrentSessionUseCase.class);

    @Test
    void publicResponseContainsNoAuditOrIdentity() throws Exception {
        when(service.current())
                .thenReturn(new DesignRevision(
                        1,
                        DesignSchema.defaults(),
                        "secret-admin",
                        "private reason",
                        "PUBLISH",
                        java.time.Instant.EPOCH));
        MockMvcBuilders.standaloneSetup(new DesignController(service))
                .build()
                .perform(get("/api/v1/config/design"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value(1))
                .andExpect(jsonPath("$.actorId").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist())
                .andExpect(jsonPath("$.tokens").isMap());
    }

    @Test
    void allAdminEndpointsDenyMissingAndUserSessions() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DesignController(service))
                .setControllerAdvice(new GlobalExceptionHandler(
                        mock(com.gole.api.common.operations.OperationalEventPublisher.class)))
                .addMappedInterceptors(
                        new String[] {"/api/admin/**"}, new AdminAuthInterceptor(sessions, new SessionCookie("false")))
                .build();
        when(sessions.resolve("")).thenReturn(Optional.empty());
        when(sessions.resolve("user")).thenReturn(Optional.of(new CurrentSession("user", "u@example.com", Role.USER)));
        for (String path : new String[] {"/api/admin/design", "/api/admin/design/history"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(get(path).header("Authorization", "Bearer user")).andExpect(status().isForbidden());
        }
        for (String path : new String[] {"/api/admin/design/publish", "/api/admin/design/restore"}) {
            mvc.perform(post(path).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(path)
                            .header("Authorization", "Bearer user")
                            .contentType("application/json")
                            .content("{}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(service);
    }

    @Test
    void adminCanReadButInvalidPublicationIsRejectedBeforeUseCase() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new DesignController(service))
                .setControllerAdvice(new GlobalExceptionHandler(
                        mock(com.gole.api.common.operations.OperationalEventPublisher.class)))
                .addMappedInterceptors(
                        new String[] {"/api/admin/**"}, new AdminAuthInterceptor(sessions, new SessionCookie("false")))
                .build();
        when(sessions.resolve("admin"))
                .thenReturn(Optional.of(new CurrentSession("admin-1", "a@example.test", Role.ADMIN)));
        when(service.current()).thenReturn(DesignRevision.initial());
        mvc.perform(get("/api/admin/design").header("Authorization", "Bearer admin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema").isArray());
        mvc.perform(post("/api/admin/design/publish")
                        .header("Authorization", "Bearer admin")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        verify(service, never()).publish(anyLong(), any(), any(), any());
    }
}
