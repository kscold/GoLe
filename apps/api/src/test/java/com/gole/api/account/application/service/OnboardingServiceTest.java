package com.gole.api.account.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gole.api.account.application.port.in.ConfirmPhoneVerificationUseCase.ConfirmPhoneVerificationCommand;
import com.gole.api.account.application.port.in.RequestPhoneVerificationUseCase.RequestPhoneVerificationCommand;
import com.gole.api.account.application.port.in.SelectInterestTagsUseCase.SelectInterestTagsCommand;
import com.gole.api.account.application.port.in.SetNicknameUseCase.SetNicknameCommand;
import com.gole.api.account.application.port.in.SubmitOnboardingConsentUseCase.SubmitConsentCommand;
import com.gole.api.account.application.port.out.AccountRepositoryPort;
import com.gole.api.account.application.port.out.PasswordHasherPort;
import com.gole.api.account.application.port.out.PhoneVerificationStorePort;
import com.gole.api.account.domain.exception.PhoneVerificationUnavailableException;
import com.gole.api.account.domain.exception.VerificationException;
import com.gole.api.account.domain.model.Account;
import com.gole.api.account.domain.model.Email;
import com.gole.api.account.domain.model.InterestTag;
import com.gole.api.account.domain.model.Nickname;
import com.gole.api.account.domain.model.PasswordHash;
import com.gole.api.account.domain.model.PhoneNumber;
import com.gole.api.account.domain.model.Role;
import com.gole.api.common.exception.ConflictException;
import com.gole.api.notification.application.port.out.AlimtalkSendException;
import com.gole.api.notification.application.port.out.AlimtalkSendException.FailureType;
import com.gole.api.notification.application.port.out.AlimtalkSenderPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 가짜 outbound port로 온보딩 유스케이스를 검증한다. (onboarding R2~R7)
 */
class OnboardingServiceTest {

    private static final String ACCOUNT_ID = "account-1";
    private static final String OTHER_ACCOUNT_ID = "account-2";

    private InMemoryAccountRepository accounts;
    private InMemoryPhoneVerificationStore phoneVerifications;
    private RecordingAlimtalkSender alimtalk;
    private PlainHasher passwordHasher;
    private MutableClock clock;
    private OnboardingService service;

    @BeforeEach
    void setUp() {
        accounts = new InMemoryAccountRepository();
        accounts.save(account(ACCOUNT_ID, "me@gole.test"));
        phoneVerifications = new InMemoryPhoneVerificationStore();
        alimtalk = new RecordingAlimtalkSender();
        passwordHasher = new PlainHasher();
        clock = new MutableClock(Instant.parse("2026-09-01T00:00:00Z"));
        service = new OnboardingService(
                accounts,
                phoneVerifications,
                () -> "123456",
                Optional.of(alimtalk),
                passwordHasher,
                properties(),
                clock);
    }

    // --- R2: 재개용 상태 조회 ---

    @Test
    void statusReportsEveryStepAsPendingForNewAccount() {
        var status = service.status(ACCOUNT_ID);

        assertThat(status.required()).isTrue();
        assertThat(status.nicknameCompleted()).isFalse();
        assertThat(status.phoneCompleted()).isFalse();
        assertThat(status.interestTagsCompleted()).isFalse();
        assertThat(status.privacyConsented()).isFalse();
        assertThat(status.legacyExempt()).isFalse();
    }

    @Test
    void statusStopsRequiringOnboardingOnceEveryStepIsDone() {
        completeOnboarding(ACCOUNT_ID);

        var status = service.status(ACCOUNT_ID);
        assertThat(status.required()).isFalse();
        assertThat(status.nickname()).isEqualTo("고레마스터");
        // 번호는 마스킹해서만 돌려준다.
        assertThat(status.maskedPhoneNumber()).isEqualTo("010-****-5678");
    }

    @Test
    void statusSkipsPhoneWhenCurrentPolicyDoesNotRequireIt() {
        OnboardingProperties phoneOptional = properties();
        phoneOptional.setPhoneVerificationRequired(false);
        OnboardingService optionalPhoneService = new OnboardingService(
                accounts,
                phoneVerifications,
                () -> "123456",
                Optional.of(alimtalk),
                passwordHasher,
                phoneOptional,
                clock);

        optionalPhoneService.setNickname(new SetNicknameCommand(ACCOUNT_ID, "고레마스터"));
        optionalPhoneService.select(new SelectInterestTagsCommand(ACCOUNT_ID, Set.of("technic")));
        optionalPhoneService.submit(new SubmitConsentCommand(ACCOUNT_ID, true, false));

        var status = optionalPhoneService.status(ACCOUNT_ID);
        assertThat(status.phoneVerificationRequired()).isFalse();
        assertThat(status.phoneCompleted()).isFalse();
        assertThat(status.required()).isFalse();
    }

    // --- R3: 닉네임 ---

    @Test
    void nicknameIsPersistedImmediately() {
        // D1: 단계마다 즉시 저장해야 이탈 후 재개가 가능하다.
        service.setNickname(new SetNicknameCommand(ACCOUNT_ID, "고레마스터"));

        assertThat(accounts.findById(ACCOUNT_ID).orElseThrow().getNickname()).isEqualTo(new Nickname("고레마스터"));
    }

    @Test
    void duplicateNicknameIsRejectedIgnoringCase() {
        accounts.save(account(OTHER_ACCOUNT_ID, "other@gole.test"));
        service.setNickname(new SetNicknameCommand(OTHER_ACCOUNT_ID, "GoLe"));

        assertThatThrownBy(() -> service.setNickname(new SetNicknameCommand(ACCOUNT_ID, "gole")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("이미 사용 중인 닉네임");
    }

    @Test
    void resettingOwnNicknameToSameValueIsAllowed() {
        // 자기 자신을 중복으로 세면 재입력이 영원히 막힌다.
        service.setNickname(new SetNicknameCommand(ACCOUNT_ID, "고레마스터"));
        service.setNickname(new SetNicknameCommand(ACCOUNT_ID, "고레마스터"));

        assertThat(accounts.findById(ACCOUNT_ID).orElseThrow().getNickname()).isEqualTo(new Nickname("고레마스터"));
    }

    // --- R4: 인증 코드 발송 ---

    @Test
    void requestSendsCodeAndReturnsMaskedNumberOnly() {
        var requested = service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "010-1234-5678"));

        assertThat(requested.maskedPhoneNumber()).isEqualTo("010-****-5678");
        assertThat(requested.expiresInSeconds()).isEqualTo(300);
        assertThat(alimtalk.sent).hasSize(1);
        assertThat(alimtalk.sent.getFirst().to()).isEqualTo("01012345678");
        assertThat(alimtalk.sent.getFirst().variables()).containsValue("123456");
    }

    @Test
    void issuedCodeIsStoredOnlyAsAHashNeverAsPlaintext() {
        // Redis 조회 권한만으로 인증을 가로챌 수 없어야 한다.
        service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));

        String storedCodeHash =
                phoneVerifications.find(ACCOUNT_ID).orElseThrow().codeHash();
        assertThat(storedCodeHash).isNotEqualTo("123456");
        assertThat(passwordHasher.matches("123456", new PasswordHash(storedCodeHash)))
                .isTrue();
    }

    @Test
    void phoneVerifiedByAnotherAccountIsRejected() {
        // D4: 1인 다계정 어뷰징 방지.
        Account other = account(OTHER_ACCOUNT_ID, "other@gole.test");
        other.markPhoneVerified(new PhoneNumber("01012345678"), clock.instant());
        accounts.save(other);

        assertThatThrownBy(() -> service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("이미 다른 계정에서 인증된");
        assertThat(alimtalk.sent).isEmpty();
    }

    @Test
    void phoneEnteredButUnverifiedByAnotherAccountDoesNotBlock() {
        // 인증하지 않은 입력까지 점유로 치면 남의 번호를 적어 두는 것만으로 영구히 막을 수 있다.
        Account other = account(OTHER_ACCOUNT_ID, "other@gole.test");
        other.selectInterestTags(Set.of("technic"));
        accounts.save(other);

        assertThat(service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"))
                        .maskedPhoneNumber())
                .isEqualTo("010-****-5678");
    }

    @Test
    void resendWithinCooldownIsRejected() {
        service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));

        assertThatThrownBy(() -> service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678")))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("60초");
        assertThat(alimtalk.sent).hasSize(1);
    }

    @Test
    void resendIsAllowedAfterCooldownExpires() {
        service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));
        phoneVerifications.advance(Duration.ofSeconds(61));

        service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));

        assertThat(alimtalk.sent).hasSize(2);
    }

    @Test
    void dailySendLimitIsEnforced() {
        for (int i = 0; i < 5; i++) {
            service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));
            phoneVerifications.advance(Duration.ofSeconds(61));
        }

        assertThatThrownBy(() -> service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678")))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("오늘 인증 요청 횟수");
        assertThat(alimtalk.sent).hasSize(5);
    }

    @Test
    void missingTemplateConfigurationStillSendsThroughWhicheverAdapterIsActive() {
        // 발송 가능 여부는 coolsms.enabled(=발송 빈의 존재)로만 갈린다 — 템플릿 ID는 실제
        // CoolsmsAlimtalkAdapter만 요구하는 값이라 여기서 따로 검증하지 않는다. 로깅 스텁처럼
        // 템플릿을 안 보는 어댑터라면 비어 있어도 그대로 통과해 코드가 로그로 남고 인증이
        // 정상 진행된다.
        OnboardingProperties unconfigured = new OnboardingProperties();
        OnboardingService noTemplate = new OnboardingService(
                accounts,
                phoneVerifications,
                () -> "123456",
                Optional.of(alimtalk),
                passwordHasher,
                unconfigured,
                clock);

        var requested = noTemplate.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));

        assertThat(requested.maskedPhoneNumber()).isEqualTo("010-****-5678");
        assertThat(alimtalk.sent).hasSize(1);
        assertThat(alimtalk.sent.getFirst().templateId()).isEmpty();
    }

    @Test
    void absentAlimtalkAdapterFailsLoudly() {
        // coolsms.enabled=false면 발송 빈 자체가 없다. 부팅은 되어야 하지만 발송은 실패해야 한다.
        OnboardingService noSender = new OnboardingService(
                accounts, phoneVerifications, () -> "123456", Optional.empty(), passwordHasher, properties(), clock);

        assertThatThrownBy(() -> noSender.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678")))
                .isInstanceOf(PhoneVerificationUnavailableException.class);
    }

    @Test
    void providerRejectionDoesNotStoreACodeTheUserCannotReceive() {
        alimtalk.failNext = true;

        assertThatThrownBy(() -> service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678")))
                .isInstanceOf(PhoneVerificationUnavailableException.class);
        assertThat(phoneVerifications.find(ACCOUNT_ID)).isEmpty();
        assertThat(phoneVerifications.isCooldownActive(ACCOUNT_ID)).isFalse();
    }

    // --- R5: 인증 코드 확인 ---

    @Test
    void confirmPersistsVerifiedPhoneAndClearsTheChallenge() {
        service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));

        service.confirm(new ConfirmPhoneVerificationCommand(ACCOUNT_ID, "123456"));

        Account saved = accounts.findById(ACCOUNT_ID).orElseThrow();
        assertThat(saved.getPhoneNumber()).isEqualTo(new PhoneNumber("01012345678"));
        assertThat(saved.getPhoneVerifiedAt()).isEqualTo(clock.instant());
        assertThat(phoneVerifications.find(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void confirmWithoutAnIssuedCodeIsRejected() {
        assertThatThrownBy(() -> service.confirm(new ConfirmPhoneVerificationCommand(ACCOUNT_ID, "123456")))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("만료");
    }

    @Test
    void fifthWrongCodeInvalidatesTheChallenge() {
        // D2: 남은 TTL 동안 무제한 대입을 막는다.
        service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));

        for (int attempt = 1; attempt <= 4; attempt++) {
            assertThatThrownBy(() -> service.confirm(new ConfirmPhoneVerificationCommand(ACCOUNT_ID, "000000")))
                    .isInstanceOf(VerificationException.class)
                    .hasMessageContaining("일치하지 않습니다");
        }
        assertThat(phoneVerifications.find(ACCOUNT_ID)).isPresent();

        assertThatThrownBy(() -> service.confirm(new ConfirmPhoneVerificationCommand(ACCOUNT_ID, "000000")))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("시도가 초과");
        assertThat(phoneVerifications.find(ACCOUNT_ID)).isEmpty();

        // 무효화된 뒤에는 정답을 넣어도 통과하지 못한다.
        assertThatThrownBy(() -> service.confirm(new ConfirmPhoneVerificationCommand(ACCOUNT_ID, "123456")))
                .isInstanceOf(VerificationException.class);
        assertThat(accounts.findById(ACCOUNT_ID).orElseThrow().getPhoneVerifiedAt())
                .isNull();
    }

    @Test
    void wrongAttemptsDoNotExtendTheCodeLifetime() {
        service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));
        assertThatThrownBy(() -> service.confirm(new ConfirmPhoneVerificationCommand(ACCOUNT_ID, "000000")))
                .isInstanceOf(VerificationException.class);

        phoneVerifications.advance(Duration.ofMinutes(5).plusSeconds(1));

        assertThat(phoneVerifications.find(ACCOUNT_ID)).isEmpty();
    }

    @Test
    void confirmRejectsNumberClaimedByAnotherAccountInTheMeantime() {
        service.request(new RequestPhoneVerificationCommand(ACCOUNT_ID, "01012345678"));
        Account other = account(OTHER_ACCOUNT_ID, "other@gole.test");
        other.markPhoneVerified(new PhoneNumber("01012345678"), clock.instant());
        accounts.save(other);

        assertThatThrownBy(() -> service.confirm(new ConfirmPhoneVerificationCommand(ACCOUNT_ID, "123456")))
                .isInstanceOf(ConflictException.class);
    }

    // --- R6, R7: 관심 태그와 동의 ---

    @Test
    void interestTagsArePersistedImmediately() {
        service.select(new SelectInterestTagsCommand(ACCOUNT_ID, Set.of("technic", "star-wars")));

        assertThat(accounts.findById(ACCOUNT_ID).orElseThrow().getInterestTags())
                .containsExactlyInAnyOrder("technic", "star-wars");
    }

    @Test
    void privacyConsentIsMandatory() {
        assertThatThrownBy(() -> service.submit(new SubmitConsentCommand(ACCOUNT_ID, false, true)))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("개인정보");
        assertThat(accounts.findById(ACCOUNT_ID).orElseThrow().getPrivacyConsentedAt())
                .isNull();
    }

    @Test
    void marketingConsentIsOptionalAndRecordedSeparately() {
        service.submit(new SubmitConsentCommand(ACCOUNT_ID, true, false));

        Account saved = accounts.findById(ACCOUNT_ID).orElseThrow();
        assertThat(saved.getPrivacyConsentedAt()).isEqualTo(clock.instant());
        assertThat(saved.getMarketingConsentedAt()).isNull();
    }

    @Test
    void marketingConsentTimestampIsRecordedWhenAgreed() {
        service.submit(new SubmitConsentCommand(ACCOUNT_ID, true, true));

        assertThat(accounts.findById(ACCOUNT_ID).orElseThrow().getMarketingConsentedAt())
                .isEqualTo(clock.instant());
    }

    @Test
    void availableTagsAreTheCuratedCatalog() {
        // 화면은 key로 저장하고 label로 보여준다 — 둘 다 실려야 한다.
        assertThat(service.availableTags()).hasSizeBetween(10, 15).contains(new InterestTag("technic", "테크닉"));
    }

    // --- 헬퍼 ---

    private void completeOnboarding(String accountId) {
        service.setNickname(new SetNicknameCommand(accountId, "고레마스터"));
        service.request(new RequestPhoneVerificationCommand(accountId, "01012345678"));
        service.confirm(new ConfirmPhoneVerificationCommand(accountId, "123456"));
        service.select(new SelectInterestTagsCommand(accountId, Set.of("technic")));
        service.submit(new SubmitConsentCommand(accountId, true, false));
    }

    private static Account account(String id, String email) {
        return Account.provisioned(id, new Email(email), new PasswordHash("plain:pw"), Role.USER);
    }

    private static OnboardingProperties properties() {
        OnboardingProperties properties = new OnboardingProperties();
        properties.setPhoneVerificationTemplateId("KA01TP000000000000000000");
        return properties;
    }

    private static final class InMemoryAccountRepository implements AccountRepositoryPort {
        private final Map<String, Account> byId = new LinkedHashMap<>();

        @Override
        public boolean existsByEmail(Email email) {
            return byId.values().stream().anyMatch(a -> a.getEmail().equals(email));
        }

        @Override
        public Account save(Account account) {
            byId.put(account.getId(), account);
            return account;
        }

        @Override
        public Optional<Account> findByEmail(Email email) {
            return byId.values().stream()
                    .filter(a -> a.getEmail().equals(email))
                    .findFirst();
        }

        @Override
        public Optional<Account> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Account> findRecent(String emailQuery, int limit) {
            return List.copyOf(byId.values());
        }

        @Override
        public long countByRole(Role role) {
            return byId.values().stream().filter(a -> a.getRole() == role).count();
        }

        @Override
        public boolean existsByNickname(Nickname nickname, String excludingAccountId) {
            return byId.values().stream()
                    .filter(a -> !a.getId().equals(excludingAccountId))
                    .anyMatch(a -> a.getNickname() != null
                            && a.getNickname().normalized().equals(nickname.normalized()));
        }

        @Override
        public boolean existsByVerifiedPhoneNumber(PhoneNumber phoneNumber, String excludingAccountId) {
            return byId.values().stream()
                    .filter(a -> !a.getId().equals(excludingAccountId))
                    .anyMatch(a -> a.getPhoneVerifiedAt() != null && phoneNumber.equals(a.getPhoneNumber()));
        }
    }

    /** TTL을 흉내 내는 가짜 Redis. {@link #advance}로 시간을 밀어 만료를 재현한다. */
    private static final class InMemoryPhoneVerificationStore implements PhoneVerificationStorePort {
        private final Map<String, Expiring<PhoneVerificationChallenge>> challenges = new HashMap<>();
        private final Map<String, Expiring<Boolean>> cooldowns = new HashMap<>();
        private final Map<String, Expiring<Long>> dailyCounts = new HashMap<>();
        private Duration elapsed = Duration.ZERO;

        void advance(Duration amount) {
            elapsed = elapsed.plus(amount);
        }

        @Override
        public boolean isCooldownActive(String accountId) {
            return alive(cooldowns.get(accountId));
        }

        @Override
        public void startCooldown(String accountId, Duration ttl) {
            cooldowns.put(accountId, new Expiring<>(true, elapsed.plus(ttl)));
        }

        @Override
        public long incrementDailySendCount(String accountId, Duration ttl) {
            Expiring<Long> current = dailyCounts.get(accountId);
            if (!alive(current)) {
                dailyCounts.put(accountId, new Expiring<>(1L, elapsed.plus(ttl)));
                return 1L;
            }
            // 만료는 처음 만들 때만 걸린다 — 갱신하지 않는다.
            Expiring<Long> next = new Expiring<>(current.value() + 1, current.expiresAt());
            dailyCounts.put(accountId, next);
            return next.value();
        }

        @Override
        public void issue(String accountId, PhoneVerificationChallenge challenge, Duration ttl) {
            challenges.put(accountId, new Expiring<>(challenge, elapsed.plus(ttl)));
        }

        @Override
        public void recordFailedAttempt(String accountId, PhoneVerificationChallenge challenge) {
            Expiring<PhoneVerificationChallenge> current = challenges.get(accountId);
            if (!alive(current)) {
                return;
            }
            challenges.put(accountId, new Expiring<>(challenge, current.expiresAt())); // TTL 유지
        }

        @Override
        public Optional<PhoneVerificationChallenge> find(String accountId) {
            Expiring<PhoneVerificationChallenge> current = challenges.get(accountId);
            return alive(current) ? Optional.of(current.value()) : Optional.empty();
        }

        @Override
        public void delete(String accountId) {
            challenges.remove(accountId);
        }

        private boolean alive(Expiring<?> entry) {
            return entry != null && elapsed.compareTo(entry.expiresAt()) < 0;
        }

        private record Expiring<T>(T value, Duration expiresAt) {}
    }

    private static final class RecordingAlimtalkSender implements AlimtalkSenderPort {
        private final List<SendAlimtalkCommand> sent = new ArrayList<>();
        private boolean failNext;

        @Override
        public AlimtalkAcceptance send(SendAlimtalkCommand command) {
            if (failNext) {
                failNext = false;
                throw new AlimtalkSendException(FailureType.PROVIDER_REJECTED, "rejected");
            }
            sent.add(command);
            return new AlimtalkAcceptance("group-1", "message-1", "2000", "OK");
        }
    }

    private static final class PlainHasher implements PasswordHasherPort {
        @Override
        public PasswordHash hash(String rawPassword) {
            return new PasswordHash("plain:" + rawPassword);
        }

        @Override
        public boolean matches(String rawPassword, PasswordHash passwordHash) {
            return rawPassword != null && passwordHash.value().equals("plain:" + rawPassword);
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
