package com.gole.api.operations;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gole.api.account.adapter.in.web.SessionCookie;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase.CurrentSession;
import com.gole.api.account.domain.model.Role;
import com.gole.api.admin.adapter.in.web.AdminAuthInterceptor;
import com.gole.api.common.exception.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OperationsAuthorizationTest {
    @Test
    void operationRouteRequiresAuthenticatedAdmin() {
        var sessions = mock(GetCurrentSessionUseCase.class);
        var guard = new AdminAuthInterceptor(sessions, new SessionCookie("auto"));
        var request = new MockHttpServletRequest("POST", "/api/admin/operations/exception-queue/runs");
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();
        when(sessions.resolve("test-token")).thenReturn(Optional.empty());
        assertThrows(UnauthorizedException.class, () -> guard.preHandle(request, response, new Object()));
        Role ordinary = java.util.Arrays.stream(Role.values())
                .filter(role -> role != Role.ADMIN)
                .findFirst()
                .orElseThrow();
        when(sessions.resolve("test-token")).thenReturn(Optional.of(new CurrentSession("user", "", ordinary)));
        assertThrows(ForbiddenException.class, () -> guard.preHandle(request, response, new Object()));
        when(sessions.resolve("test-token")).thenReturn(Optional.of(new CurrentSession("admin", "", Role.ADMIN)));
        assertTrue(guard.preHandle(request, response, new Object()));
        assertEquals("admin", request.getAttribute(AdminAuthInterceptor.ATTR_ACCOUNT_ID));
    }
}
