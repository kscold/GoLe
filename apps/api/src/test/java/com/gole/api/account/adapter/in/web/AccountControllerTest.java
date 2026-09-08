package com.gole.api.account.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.gole.api.account.adapter.in.web.AccountRequests.RegisterRequest;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase.CurrentSession;
import com.gole.api.account.application.port.in.LogoutUseCase;
import com.gole.api.account.application.port.in.PublicAuthRequestLimitUseCase;
import com.gole.api.account.application.port.in.RefreshSessionUseCase;
import com.gole.api.account.application.port.in.RefreshSessionUseCase.RefreshSessionResult;
import com.gole.api.account.application.port.in.RegisterAccountUseCase;
import com.gole.api.account.application.port.in.RegisterAccountUseCase.RegisterAccountCommand;
import com.gole.api.account.application.port.in.ResendVerificationUseCase;
import com.gole.api.account.application.port.in.SignInUseCase;
import com.gole.api.account.application.port.in.VerifyEmailUseCase;
import com.gole.api.account.config.EmailAuthenticationAvailability;
import com.gole.api.account.domain.exception.EmailAlreadyRegisteredException;
import com.gole.api.account.domain.model.Role;
import com.gole.api.common.exception.ServiceUnavailableException;
import com.gole.api.common.web.ClientAddressResolver;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountControllerTest {

    private RefreshSessionUseCase refreshSessions;
    private RegisterAccountUseCase registerAccounts;
    private ResendVerificationUseCase resendVerifications;
    private PublicAuthRequestLimitUseCase publicRequestLimit;
    private GetCurrentSessionUseCase getCurrentSessionUseCase;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        refreshSessions = mock(RefreshSessionUseCase.class);
        registerAccounts = mock(RegisterAccountUseCase.class);
        resendVerifications = mock(ResendVerificationUseCase.class);
        publicRequestLimit = mock(PublicAuthRequestLimitUseCase.class);
        getCurrentSessionUseCase = mock(GetCurrentSessionUseCase.class);
        var controller = new AccountController(
                registerAccounts,
                resendVerifications,
                mock(VerifyEmailUseCase.class),
                mock(SignInUseCase.class),
                getCurrentSessionUseCase,
                mock(LogoutUseCase.class),
                refreshSessions,
                new SessionCookie("false", Duration.ofDays(7)),
                publicRequestLimit,
                new ClientAddressResolver(),
                new EmailAuthenticationAvailability("test", false));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void me_returnsNickname_whenOnboardingHasSetOne() throws Exception {
        when(getCurrentSessionUseCase.resolve("token-1"))
                .thenReturn(
                        Optional.of(new CurrentSession("account-1", "member@gole.test", Role.USER, false, "구글가입자")));

        mvc.perform(get("/api/v1/accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer token-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("account-1"))
                .andExpect(jsonPath("$.email").value("member@gole.test"))
                .andExpect(jsonPath("$.nickname").value("구글가입자"));
    }

    @Test
    void me_returnsNullNickname_whenOnboardingHasNotSetOne() throws Exception {
        when(getCurrentSessionUseCase.resolve("token-2"))
                .thenReturn(Optional.of(new CurrentSession("account-2", "member2@gole.test", Role.USER)));

        // doesNotExist()는 값이 null이어도 통과해 두 경우를 구분하지 못한다. 클라이언트는
        // 필드가 null로 내려오는 쪽에 맞춰져 있으므로 그것을 그대로 못박는다.
        mvc.perform(get("/api/v1/accounts/me").header(HttpHeaders.AUTHORIZATION, "Bearer token-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value(nullValue()));
    }

    @Test
    void registerMapsExplicitPolicyAcceptanceToUseCase() throws Exception {
        when(registerAccounts.register(org.mockito.ArgumentMatchers.any())).thenReturn("account-1");

        mvc.perform(
                        post("/api/v1/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "email": "member@gole.test",
                                  "password": "password1",
                                  "termsVersion": "2026-09-04",
                                  "privacyVersion": "2026-09-05",
                                  "termsAccepted": true,
                                  "privacyAcknowledged": true,
                                  "minimumAgeConfirmed": true,
                                  "thirdPartyProvisionVersion": "2026-09-04",
                                  "thirdPartyProvisionAccepted": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("registration-pending"));

        ArgumentCaptor<RegisterAccountCommand> command = ArgumentCaptor.forClass(RegisterAccountCommand.class);
        verify(registerAccounts).register(command.capture());
        verify(publicRequestLimit).acquireRegistration("member@gole.test", "127.0.0.1");
        assertThat(command.getValue().policyAcceptance().termsVersion()).isEqualTo("2026-09-04");
        assertThat(command.getValue().policyAcceptance().privacyVersion()).isEqualTo("2026-09-05");
        assertThat(command.getValue().policyAcceptance().privacyAcknowledged()).isTrue();
        assertThat(command.getValue().policyAcceptance().minimumAgeConfirmed()).isTrue();
        assertThat(command.getValue().policyAcceptance().thirdPartyProvisionVersion())
                .isEqualTo("2026-09-04");
        assertThat(command.getValue().policyAcceptance().thirdPartyProvisionAccepted())
                .isTrue();
    }

    @Test
    void registerDoesNotDiscloseWhetherEmailAlreadyExists() throws Exception {
        when(registerAccounts.register(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new EmailAlreadyRegisteredException("member@gole.test"));

        mvc.perform(
                        post("/api/v1/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "email": "member@gole.test",
                                  "password": "password1",
                                  "termsVersion": "2026-09-04",
                                  "privacyVersion": "2026-09-05",
                                  "termsAccepted": true,
                                  "privacyAcknowledged": true,
                                  "minimumAgeConfirmed": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value("registration-pending"));
    }

    @Test
    void registerRejectsUncheckedRequiredPolicyBeforeUseCase() throws Exception {
        mvc.perform(
                        post("/api/v1/accounts")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {
                                  "email": "member@gole.test",
                                  "password": "password1",
                                  "termsVersion": "2026-09-04",
                                  "privacyVersion": "2026-09-05",
                                  "termsAccepted": false,
                                  "privacyAcknowledged": true,
                                  "minimumAgeConfirmed": true
                                }
                                """))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verifyNoInteractions(registerAccounts);
    }

    @Test
    void unavailableEmailRegistrationRejectsBeforeRateLimitMutation() {
        var controller = new AccountController(
                registerAccounts,
                resendVerifications,
                mock(VerifyEmailUseCase.class),
                mock(SignInUseCase.class),
                mock(GetCurrentSessionUseCase.class),
                mock(LogoutUseCase.class),
                refreshSessions,
                new SessionCookie("false", Duration.ofDays(7)),
                publicRequestLimit,
                new ClientAddressResolver(),
                new EmailAuthenticationAvailability("production", false));

        assertThatThrownBy(() -> controller.register(
                        new RegisterRequest(
                                "member@gole.test",
                                "password1",
                                "2026-09-04",
                                "2026-09-05",
                                true,
                                true,
                                true,
                                "2026-09-04",
                                false),
                        new MockHttpServletRequest()))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasFieldOrPropertyWithValue("code", EmailAuthenticationAvailability.UNAVAILABLE_CODE);
        verifyNoInteractions(publicRequestLimit, registerAccounts);
    }

    @Test
    void resendCooldownReturnsNoContentWithoutLookingUpAccount() throws Exception {
        when(publicRequestLimit.acquireVerificationResend("member@gole.test", "127.0.0.1"))
                .thenReturn(false);

        mvc.perform(
                        post("/api/v1/accounts/verification/resend")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                {"email":"member@gole.test"}
                                """))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.verifyNoInteractions(resendVerifications);
    }

    @Test
    void cookieRefreshRotatesHttpOnlyCookieWithoutExposingTokenInJson() throws Exception {
        when(refreshSessions.refresh("old-cookie"))
                .thenReturn(Optional.of(
                        new RefreshSessionResult("account-1", "new-cookie", Role.USER, true, Duration.ofDays(6))));

        mvc.perform(post("/api/v1/accounts/sessions/refresh").cookie(new Cookie(SessionCookie.NAME, "old-cookie")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value("account-1"))
                .andExpect(jsonPath("$.sessionToken").value(""))
                .andExpect(jsonPath("$.rotated").value(true))
                .andExpect(header().string(
                                HttpHeaders.SET_COOKIE,
                                org.hamcrest.Matchers.containsString("gole_session=new-cookie")));
    }

    @Test
    void bearerRefreshReturnsReplacementWithoutSettingBrowserCookie() throws Exception {
        when(refreshSessions.refresh("old-bearer"))
                .thenReturn(Optional.of(
                        new RefreshSessionResult("account-1", "new-bearer", Role.ADMIN, true, Duration.ofDays(6))));

        mvc.perform(post("/api/v1/accounts/sessions/refresh").header(HttpHeaders.AUTHORIZATION, "Bearer old-bearer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionToken").value("new-bearer"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }
}
