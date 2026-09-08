package com.gole.api.shipping.adapter.in.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.gole.api.account.adapter.in.web.SessionCookie;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase.CurrentSession;
import com.gole.api.account.domain.model.Role;
import com.gole.api.admin.adapter.in.web.AdminAuthInterceptor;
import com.gole.api.common.operations.OperationalEventPublisher;
import com.gole.api.common.web.GlobalExceptionHandler;
import com.gole.api.shipping.application.port.in.ManageTrackerUseCase;
import com.gole.api.shipping.application.port.out.DeliveryTrackerPort.Diagnostics;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TrackerAdminControllerTest {
    private static final String BASE = "/api/admin/integrations/tracker";
    private final ManageTrackerUseCase service = mock(ManageTrackerUseCase.class);
    private final GetCurrentSessionUseCase sessions = mock(GetCurrentSessionUseCase.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new TrackerAdminController(service))
            .addInterceptors(new AdminAuthInterceptor(sessions, new SessionCookie("false")))
            .setControllerAdvice(new GlobalExceptionHandler(mock(OperationalEventPublisher.class)))
            .build();

    @Test
    void unauthenticatedRequestsAreRejectedBeforeUseCase() throws Exception {
        when(sessions.resolve(anyString())).thenReturn(Optional.empty());
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        mvc.perform(post(BASE + "/verify")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void nonAdminIsForbidden() throws Exception {
        when(sessions.resolve(anyString())).thenReturn(Optional.of(new CurrentSession("user", "", Role.USER)));
        mvc.perform(post(BASE + "/verify")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void adminCanReadMaskedDiagnosticsAndVerify() throws Exception {
        when(sessions.resolve(anyString())).thenReturn(Optional.of(new CurrentSession("admin", "", Role.ADMIN)));
        var diagnostics = new Diagnostics(false, false, false, null, null, null);
        when(service.status()).thenReturn(diagnostics);
        when(service.verify("admin")).thenReturn(diagnostics);
        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.clientSecret").doesNotExist())
                .andExpect(jsonPath("$.clientId").doesNotExist());
        mvc.perform(post(BASE + "/verify")).andExpect(status().isOk());
        verify(service).verify("admin");
    }

    @Test
    void validatesLookupBodiesBeforeUseCase() throws Exception {
        when(sessions.resolve(anyString())).thenReturn(Optional.of(new CurrentSession("admin", "", Role.ADMIN)));
        mvc.perform(post(BASE + "/sample")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"carrier\":\"hanjin\",\"waybillNumber\":\"http://localhost\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE + "/requery")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"../secret\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
