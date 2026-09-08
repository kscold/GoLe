package com.gole.api.account.application.port.out;

import java.time.Duration;
import java.util.Optional;

/**
 * Outbound port: 전화번호 인증 코드(OTP) 임시 저장소. (onboarding D2)
 *
 * <p>이메일 인증 코드는 {@code Account} 애그리거트 안에 상태가 결합돼 있어 재사용하면
 * 이메일 인증 상태와 충돌한다. 그래서 OTP·쿨다운·일일 카운터는 계정 밖 TTL 저장소에 둔다.
 */
public interface PhoneVerificationStorePort {

    /** 재발송 쿨다운이 아직 살아 있는가. */
    boolean isCooldownActive(String accountId);

    /** 재발송 쿨다운 시작. */
    void startCooldown(String accountId, Duration ttl);

    /**
     * 오늘 발송 횟수를 1 증가시키고 증가 후 값을 돌려준다.
     *
     * @param ttl 카운터를 처음 만들 때 적용할 유효 기간(자정이 아니라 최초 발송 기준 24시간)
     */
    long incrementDailySendCount(String accountId, Duration ttl);

    /** 새로 발급한 코드를 TTL과 함께 저장한다. 기존 코드가 있으면 덮어쓴다. */
    void issue(String accountId, PhoneVerificationChallenge challenge, Duration ttl);

    /**
     * 오답 횟수만 갱신한다. <b>남은 TTL은 늘리지 않는다.</b>
     *
     * <p>틀릴 때마다 TTL을 다시 5분으로 밀면 공격자가 오답을 반복하는 것만으로 대입 창을
     * 무한히 연장할 수 있다.
     */
    void recordFailedAttempt(String accountId, PhoneVerificationChallenge challenge);

    Optional<PhoneVerificationChallenge> find(String accountId);

    /** 인증 성공 또는 시도 횟수 초과 시 무효화. */
    void delete(String accountId);

    /** 계정 파기 시 OTP뿐 아니라 쿨다운·일일 한도 키까지 함께 제거한다. */
    default void deleteAllForAccount(String accountId) {
        delete(accountId);
    }

    /**
     * 발급된 인증 시도.
     *
     * @param phoneNumber 정규화된(숫자만) 번호. 확인 시점에 이 번호로 계정에 기록한다.
     * @param storedCode 대조에 쓸 저장 형태. 기본은 {@code PasswordHasherPort}로 해싱한 단방향
     *     값이라 Redis 조회 권한만으로는 인증을 가로챌 수 없다. {@code PhoneVerificationCodeExposurePolicy}가
     *     허용한 개발 환경에서만 평문이 들어온다 — 그래서 이름이 {@code codeHash}가 아니다.
     *     어느 형태인지는 저장한 쪽과 대조하는 쪽이 같은 정책을 보고 판단하며, 저장소는 모른다.
     * @param attempts 지금까지의 오답 횟수
     */
    record PhoneVerificationChallenge(String phoneNumber, String storedCode, int attempts) {

        public PhoneVerificationChallenge withOneMoreAttempt() {
            return new PhoneVerificationChallenge(phoneNumber, storedCode, attempts + 1);
        }
    }
}
