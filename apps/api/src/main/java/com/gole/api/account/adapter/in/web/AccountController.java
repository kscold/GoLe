package com.gole.api.account.adapter.in.web;

import com.gole.api.account.adapter.in.web.AccountRequests.RegisterRequest;
import com.gole.api.account.adapter.in.web.AccountRequests.ResendVerificationRequest;
import com.gole.api.account.adapter.in.web.AccountRequests.SignInRequest;
import com.gole.api.account.adapter.in.web.AccountRequests.VerifyEmailRequest;
import com.gole.api.account.adapter.in.web.AccountResponses.MeResponse;
import com.gole.api.account.adapter.in.web.AccountResponses.RefreshSessionResponse;
import com.gole.api.account.adapter.in.web.AccountResponses.RegisterResponse;
import com.gole.api.account.adapter.in.web.AccountResponses.SignInResponse;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase;
import com.gole.api.account.application.port.in.GetCurrentSessionUseCase.CurrentSession;
import com.gole.api.account.application.port.in.LogoutUseCase;
import com.gole.api.account.application.port.in.PublicAuthRequestLimitUseCase;
import com.gole.api.account.application.port.in.RefreshSessionUseCase;
import com.gole.api.account.application.port.in.RegisterAccountUseCase;
import com.gole.api.account.application.port.in.RegisterAccountUseCase.RegisterAccountCommand;
import com.gole.api.account.application.port.in.ResendVerificationUseCase;
import com.gole.api.account.application.port.in.ResendVerificationUseCase.ResendVerificationCommand;
import com.gole.api.account.application.port.in.SignInUseCase;
import com.gole.api.account.application.port.in.SignInUseCase.SignInCommand;
import com.gole.api.account.application.port.in.SignInUseCase.SignInResult;
import com.gole.api.account.application.port.in.VerifyEmailUseCase;
import com.gole.api.account.application.port.in.VerifyEmailUseCase.VerifyEmailCommand;
import com.gole.api.account.config.EmailAuthenticationAvailability;
import com.gole.api.account.domain.exception.EmailAlreadyRegisteredException;
import com.gole.api.account.domain.model.SignupPolicyAcceptance;
import com.gole.api.common.exception.UnauthorizedException;
import com.gole.api.common.web.ClientAddressResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inbound 어댑터(REST). use case 인터페이스에만 의존한다.
 */
@Tag(name = "Account", description = "회원가입·인증·로그인·로그아웃·내정보")
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private static final String PUBLIC_REGISTRATION_REFERENCE = "registration-pending";

    private final RegisterAccountUseCase registerAccountUseCase;
    private final ResendVerificationUseCase resendVerificationUseCase;
    private final VerifyEmailUseCase verifyEmailUseCase;
    private final SignInUseCase signInUseCase;
    private final GetCurrentSessionUseCase getCurrentSessionUseCase;
    private final LogoutUseCase logoutUseCase;
    private final RefreshSessionUseCase refreshSessionUseCase;
    private final SessionCookie sessionCookie;
    private final PublicAuthRequestLimitUseCase publicRequestLimit;
    private final ClientAddressResolver clientAddresses;
    private final EmailAuthenticationAvailability emailAuthentication;

    public AccountController(
            RegisterAccountUseCase registerAccountUseCase,
            ResendVerificationUseCase resendVerificationUseCase,
            VerifyEmailUseCase verifyEmailUseCase,
            SignInUseCase signInUseCase,
            GetCurrentSessionUseCase getCurrentSessionUseCase,
            LogoutUseCase logoutUseCase,
            RefreshSessionUseCase refreshSessionUseCase,
            SessionCookie sessionCookie,
            PublicAuthRequestLimitUseCase publicRequestLimit,
            ClientAddressResolver clientAddresses,
            EmailAuthenticationAvailability emailAuthentication) {
        this.registerAccountUseCase = registerAccountUseCase;
        this.resendVerificationUseCase = resendVerificationUseCase;
        this.verifyEmailUseCase = verifyEmailUseCase;
        this.signInUseCase = signInUseCase;
        this.getCurrentSessionUseCase = getCurrentSessionUseCase;
        this.logoutUseCase = logoutUseCase;
        this.refreshSessionUseCase = refreshSessionUseCase;
        this.sessionCookie = sessionCookie;
        this.publicRequestLimit = publicRequestLimit;
        this.clientAddresses = clientAddresses;
        this.emailAuthentication = emailAuthentication;
    }

    @Operation(summary = "회원가입", description = "이메일 발송이 준비된 때만 요청을 접수하며, 계정 존재 여부와 무관하게 같은 성공 응답을 반환합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "201", description = "가입 요청 접수 — 계정 존재 여부는 반환하지 않음"),
        @ApiResponse(responseCode = "503", description = "이메일 가입 준비 중")
    })
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
        emailAuthentication.requireAvailable();
        publicRequestLimit.acquireRegistration(request.email(), clientAddresses.resolve(http));
        try {
            registerAccountUseCase.register(new RegisterAccountCommand(
                    request.email(),
                    request.password(),
                    new SignupPolicyAcceptance(
                            request.termsVersion(),
                            request.privacyVersion(),
                            request.termsAccepted(),
                            request.privacyAcknowledged(),
                            request.minimumAgeConfirmed(),
                            request.thirdPartyProvisionVersion(),
                            Boolean.TRUE.equals(request.thirdPartyProvisionAccepted()))));
        } catch (EmailAlreadyRegisteredException duplicate) {
            // 공개 응답에서 기존 가입 여부와 내부 accountId를 구분하지 않는다. 가입 화면은
            // 응답 ID를 사용하지 않고 사용자가 입력한 이메일로 인증 단계에 진입한다.
        }
        return new RegisterResponse(PUBLIC_REGISTRATION_REFERENCE);
    }

    @Operation(summary = "이메일 인증", description = "이메일 발송이 준비된 때 가입 시 받은 인증 코드로 계정을 활성화합니다.")
    @PostMapping("/verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verify(@Valid @RequestBody VerifyEmailRequest request) {
        emailAuthentication.requireAvailable();
        verifyEmailUseCase.verify(new VerifyEmailCommand(request.email(), request.code()));
    }

    @Operation(summary = "이메일 인증 코드 재발급", description = "이메일 발송이 준비된 때 인증 대기 계정에 새 코드를 발송합니다. 60초 재요청 제한이 적용됩니다.")
    @PostMapping("/verification/resend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@Valid @RequestBody ResendVerificationRequest request, HttpServletRequest http) {
        emailAuthentication.requireAvailable();
        if (!publicRequestLimit.acquireVerificationResend(request.email(), clientAddresses.resolve(http))) {
            return;
        }
        resendVerificationUseCase.resend(new ResendVerificationCommand(request.email()));
    }

    @Operation(summary = "로그인", description = "이메일·비밀번호로 인증하고 브라우저용 HttpOnly 쿠키와 외부 API용 Bearer 세션 토큰을 발급합니다.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "로그인 성공 — sessionToken 반환"),
        @ApiResponse(responseCode = "401", description = "이메일/비밀번호 불일치"),
        @ApiResponse(responseCode = "423", description = "로그인 잠금(5회 실패)")
    })
    @PostMapping("/sessions")
    public SignInResponse signIn(
            @Valid @RequestBody SignInRequest request, HttpServletRequest http, HttpServletResponse response) {
        SignInResult result = signInUseCase.signIn(new SignInCommand(request.email(), request.password()));
        sessionCookie.issue(http, response, result.sessionToken());
        return new SignInResponse(
                result.accountId(), result.sessionToken(), result.role().name(), result.onboardingRequired());
    }

    @Operation(summary = "세션 갱신", description = "현재 세션을 재검증하고 회전 주기가 지난 불투명 토큰만 교체합니다. 최초 발급 시각은 보존됩니다.")
    @PostMapping("/sessions/refresh")
    public RefreshSessionResponse refreshSession(HttpServletRequest request, HttpServletResponse response) {
        var result = refreshSessionUseCase
                .refresh(sessionCookie.resolve(request))
                .orElseThrow(() -> new UnauthorizedException("INVALID_SESSION", "유효한 세션이 아닙니다"));
        boolean bearerClient = sessionCookie.usesBearer(request);
        if (result.rotated() && !bearerClient) {
            sessionCookie.issue(request, response, result.sessionToken(), result.remainingLifetime());
        }
        return new RefreshSessionResponse(
                result.accountId(),
                bearerClient ? result.sessionToken() : "",
                result.role().name(),
                result.rotated());
    }

    @Operation(
            summary = "내 정보 조회",
            description = "현재 세션의 계정 ID·이메일·권한·닉네임을 반환합니다. 온보딩에서 닉네임을 아직 설정하지 않았다면 null입니다. "
                    + "브라우저 쿠키 또는 Authorization: Bearer {token}이 필요합니다.")
    @GetMapping("/me")
    public MeResponse me(HttpServletRequest request) {
        String token = sessionCookie.resolve(request);
        CurrentSession session = getCurrentSessionUseCase
                .resolve(token)
                .orElseThrow(() -> new UnauthorizedException("INVALID_SESSION", "유효한 세션이 아닙니다"));
        return new MeResponse(
                session.accountId(),
                session.email(),
                session.role().name(),
                session.onboardingRequired(),
                session.nickname());
    }

    @Operation(summary = "로그아웃", description = "브라우저 쿠키 또는 Bearer 토큰에 연결된 서버 세션을 폐기하고 쿠키를 삭제합니다.")
    @DeleteMapping("/sessions")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        logoutUseCase.logout(sessionCookie.resolve(request));
        sessionCookie.clear(request, response);
    }
}
