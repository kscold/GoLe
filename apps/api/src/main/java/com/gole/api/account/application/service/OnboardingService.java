package com.gole.api.account.application.service;

import com.gole.api.account.application.port.in.ConfirmPhoneVerificationUseCase;
import com.gole.api.account.application.port.in.GetOnboardingStatusUseCase;
import com.gole.api.account.application.port.in.ListInterestTagsUseCase;
import com.gole.api.account.application.port.in.RequestPhoneVerificationUseCase;
import com.gole.api.account.application.port.in.SelectInterestTagsUseCase;
import com.gole.api.account.application.port.in.SetNicknameUseCase;
import com.gole.api.account.application.port.in.SubmitOnboardingConsentUseCase;
import com.gole.api.account.application.port.out.AccountRepositoryPort;
import com.gole.api.account.application.port.out.PasswordHasherPort;
import com.gole.api.account.application.port.out.PhoneVerificationStorePort;
import com.gole.api.account.application.port.out.PhoneVerificationStorePort.PhoneVerificationChallenge;
import com.gole.api.account.application.port.out.VerificationCodeGeneratorPort;
import com.gole.api.account.config.PhoneVerificationCodeExposurePolicy;
import com.gole.api.account.domain.exception.PhoneVerificationUnavailableException;
import com.gole.api.account.domain.exception.VerificationException;
import com.gole.api.account.domain.model.Account;
import com.gole.api.account.domain.model.InterestTag;
import com.gole.api.account.domain.model.InterestTagCatalog;
import com.gole.api.account.domain.model.Nickname;
import com.gole.api.account.domain.model.OnboardingProfile;
import com.gole.api.account.domain.model.PasswordHash;
import com.gole.api.account.domain.model.PhoneNumber;
import com.gole.api.common.exception.ConflictException;
import com.gole.api.common.exception.NotFoundException;
import com.gole.api.notification.application.port.out.AlimtalkSendException;
import com.gole.api.notification.application.port.out.AlimtalkSenderPort;
import com.gole.api.notification.application.port.out.AlimtalkSenderPort.SendAlimtalkCommand;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 온보딩 유스케이스 구현. (onboarding R2~R7)
 *
 * <p><b>각 단계는 성공하는 즉시 저장한다</b>(D1). 끝에서 한 번에 커밋하면 중간에 이탈한
 * 사용자가 다음 로그인 때 처음부터 다시 해야 하는데, 온보딩은 이탈률이 가장 높은 구간이다.
 *
 * <p>OTP 발송은 신규 포트를 만들지 않고 notification 컨텍스트의 {@link AlimtalkSenderPort}를
 * 그대로 호출한다(D3). 실제 CoolSMS 빈이나 로컬 전용 로깅 빈이 없는 구성도 부팅할 수 있도록
 * Optional로 주입한다. 공개 환경의 로깅 빈은 요청을 성공처럼 처리하지 않고 실패로 닫힌다.
 *
 * <p>OTP 저장 형태는 {@link PhoneVerificationCodeExposurePolicy}가 정한다 — 기본은 단방향 해시고,
 * 실제 발송이 없는 개발 환경에서 명시적으로 옵트인했을 때만 평문이다.
 */
@Service
public class OnboardingService
        implements GetOnboardingStatusUseCase,
                ListInterestTagsUseCase,
                SetNicknameUseCase,
                RequestPhoneVerificationUseCase,
                ConfirmPhoneVerificationUseCase,
                SelectInterestTagsUseCase,
                SubmitOnboardingConsentUseCase {

    private static final Duration OTP_TTL = Duration.ofMinutes(5); // D2
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60); // 이메일 인증과 동일 정책
    private static final Duration DAILY_WINDOW = Duration.ofHours(24);
    private static final long MAX_DAILY_SENDS = 5;
    private static final int MAX_CONFIRM_ATTEMPTS = 5;

    private final AccountRepositoryPort accountRepository;
    private final PhoneVerificationStorePort phoneVerifications;
    private final VerificationCodeGeneratorPort codeGenerator;
    private final Optional<AlimtalkSenderPort> alimtalkSender;
    private final PasswordHasherPort passwordHasher;
    private final PhoneVerificationCodeExposurePolicy codeExposure;
    private final OnboardingProperties properties;
    private final Clock clock;

    public OnboardingService(
            AccountRepositoryPort accountRepository,
            PhoneVerificationStorePort phoneVerifications,
            VerificationCodeGeneratorPort codeGenerator,
            Optional<AlimtalkSenderPort> alimtalkSender,
            PasswordHasherPort passwordHasher,
            PhoneVerificationCodeExposurePolicy codeExposure,
            OnboardingProperties properties,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.phoneVerifications = phoneVerifications;
        this.codeGenerator = codeGenerator;
        this.alimtalkSender = alimtalkSender;
        this.passwordHasher = passwordHasher;
        this.codeExposure = codeExposure;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public OnboardingStatus status(String accountId) {
        OnboardingProfile profile = require(accountId).getOnboarding();
        return new OnboardingStatus(
                accountId,
                profile.hasNickname(),
                profile.hasNickname() ? profile.nickname().value() : null,
                properties.phoneVerificationRequired(),
                profile.isPhoneVerified(),
                profile.isPhoneVerified() ? profile.phoneNumber().masked() : null,
                profile.hasInterestTags(),
                List.copyOf(profile.interestTags()),
                profile.hasPrivacyConsent(),
                profile.marketingConsentedAt() != null,
                profile.isRequired(properties.phoneVerificationRequired()),
                profile.legacyExempt());
    }

    @Override
    public List<InterestTag> availableTags() {
        return InterestTagCatalog.tags();
    }

    @Override
    public void setNickname(SetNicknameCommand command) {
        Account account = require(command.accountId());
        Nickname nickname = new Nickname(command.nickname()); // D9 형식 검증
        if (accountRepository.existsByNickname(nickname, account.getId())) {
            throw new ConflictException("NICKNAME_ALREADY_IN_USE", "이미 사용 중인 닉네임입니다");
        }
        account.changeNickname(nickname);
        accountRepository.save(account);
    }

    @Override
    public PhoneVerificationRequested request(RequestPhoneVerificationCommand command) {
        Account account = require(command.accountId());
        PhoneNumber phoneNumber = new PhoneNumber(command.phoneNumber()); // D4 형식 검증
        requirePhoneNotTaken(phoneNumber, account.getId());

        // 한도를 먼저 본다 — 발송에 성공한 뒤 한도를 세면 초과분이 이미 나간 뒤가 된다.
        if (phoneVerifications.isCooldownActive(account.getId())) {
            throw new VerificationException("PHONE_VERIFICATION_RESEND_TOO_SOON", "인증 코드는 60초 후 다시 요청할 수 있습니다");
        }
        if (phoneVerifications.incrementDailySendCount(account.getId(), DAILY_WINDOW) > MAX_DAILY_SENDS) {
            throw new VerificationException(
                    "PHONE_VERIFICATION_DAILY_LIMIT_EXCEEDED", "오늘 인증 요청 횟수를 모두 사용했습니다. 내일 다시 시도해 주세요");
        }

        String code = codeGenerator.generateCode();
        sendCode(phoneNumber, code);

        // 공개 환경은 항상 해시다. 실제 발송이 없는 개발 환경에서만, 그것도 명시적 옵트인이
        // 있을 때만 평문으로 남겨 개발자가 Redis나 로그에서 코드를 꺼내 흐름을 밟아볼 수 있게 한다.
        String storedCode = codeExposure.plaintextAllowed()
                ? code
                : passwordHasher.hash(code).value();
        phoneVerifications.issue(
                account.getId(), new PhoneVerificationChallenge(phoneNumber.value(), storedCode, 0), OTP_TTL);
        phoneVerifications.startCooldown(account.getId(), RESEND_COOLDOWN);
        return new PhoneVerificationRequested(phoneNumber.masked(), OTP_TTL.toSeconds());
    }

    @Override
    public void confirm(ConfirmPhoneVerificationCommand command) {
        Account account = require(command.accountId());
        PhoneVerificationChallenge challenge = phoneVerifications
                .find(account.getId())
                .orElseThrow(() ->
                        new VerificationException("PHONE_VERIFICATION_CODE_MISSING", "인증 코드가 만료되었습니다. 다시 요청해 주세요"));

        if (!codeMatches(command.code(), challenge.storedCode())) {
            PhoneVerificationChallenge retried = challenge.withOneMoreAttempt();
            if (retried.attempts() >= MAX_CONFIRM_ATTEMPTS) {
                // 5회 오답이면 해당 OTP를 무효화한다(D2) — 남은 TTL 동안 무제한 대입을 막는다.
                phoneVerifications.delete(account.getId());
                throw new VerificationException(
                        "PHONE_VERIFICATION_TOO_MANY_ATTEMPTS", "인증 시도가 초과되었습니다. 코드를 다시 요청해 주세요");
            }
            phoneVerifications.recordFailedAttempt(account.getId(), retried);
            throw new VerificationException("PHONE_VERIFICATION_CODE_MISMATCH", "인증 코드가 일치하지 않습니다");
        }

        PhoneNumber phoneNumber = new PhoneNumber(challenge.phoneNumber());
        // 발송 시점 이후 다른 계정이 같은 번호를 인증했을 수 있다. 저장 직전에 한 번 더 본다.
        requirePhoneNotTaken(phoneNumber, account.getId());

        account.markPhoneVerified(phoneNumber, Instant.now(clock));
        accountRepository.save(account);
        phoneVerifications.delete(account.getId());
    }

    @Override
    public void select(SelectInterestTagsCommand command) {
        Account account = require(command.accountId());
        Set<String> tags = InterestTagCatalog.validateSelection(command.tags());
        account.selectInterestTags(tags);
        accountRepository.save(account);
    }

    @Override
    public void submit(SubmitConsentCommand command) {
        Account account = require(command.accountId());
        account.consent(command.privacyConsented(), command.marketingConsented(), Instant.now(clock));
        accountRepository.save(account);
    }

    private void requirePhoneNotTaken(PhoneNumber phoneNumber, String accountId) {
        if (accountRepository.existsByVerifiedPhoneNumber(phoneNumber, accountId)) {
            throw new ConflictException("PHONE_ALREADY_IN_USE", "이미 다른 계정에서 인증된 전화번호입니다");
        }
    }

    /**
     * 입력 코드가 저장된 형태와 일치하는가.
     *
     * <p>저장할 때와 같은 정책을 본다. 발급 직후 정책이 뒤집히면(설정 변경 + 재기동) 남은 TTL
     * 동안의 코드는 대조에 실패하는데, 이는 사용자가 재요청하면 풀리는 쪽이라 그대로 둔다 —
     * 반대로 저장 형태를 추측해서 맞춰 주면 평문을 해시로 착각시키는 경로가 생긴다.
     */
    private boolean codeMatches(String submitted, String storedCode) {
        if (!codeExposure.plaintextAllowed()) {
            return passwordHasher.matches(submitted, new PasswordHash(storedCode));
        }
        // 평문 경로에도 상수 시간 비교를 쓴다. 개발 전용이지만 String.equals의 조기 반환을
        // 그대로 두면 이 분기가 타이밍 실험의 연습장이 된다.
        return MessageDigest.isEqual(
                submitted.getBytes(StandardCharsets.UTF_8), storedCode.getBytes(StandardCharsets.UTF_8));
    }

    private void sendCode(PhoneNumber phoneNumber, String code) {
        // 템플릿 요구는 실제 CoolSMS 어댑터가 소유한다. 로컬 로깅 어댑터는 빈 템플릿으로도
        // 개발 흐름을 재현하지만, staging/production에서는 요청을 실패로 닫는다. 승인된
        // 템플릿이 필요한 실제 어댑터의 요구는
        // CoolsmsAlimtalkAdapter.validate()가 이미 강제한다(빈 값이면 AlimtalkSendException).
        if (alimtalkSender.isEmpty()) {
            throw new PhoneVerificationUnavailableException();
        }
        String templateId = properties.phoneVerificationTemplateId();
        try {
            alimtalkSender
                    .get()
                    .send(new SendAlimtalkCommand(
                            phoneNumber.value(), templateId, Map.of(properties.phoneVerificationCodeVariable(), code)));
        } catch (AlimtalkSendException ex) {
            // 접수 실패를 성공으로 넘기면 사용자는 오지 않는 코드를 기다린다. 코드를 저장하기
            // 전에 던져서 "재요청" 버튼이 곧바로 살아 있게 한다(쿨다운도 아직 걸리지 않았다).
            throw new PhoneVerificationUnavailableException();
        }
    }

    private Account require(String accountId) {
        return accountRepository
                .findById(accountId)
                .orElseThrow(() -> new NotFoundException("ACCOUNT_NOT_FOUND", "계정을 찾을 수 없습니다"));
    }
}
