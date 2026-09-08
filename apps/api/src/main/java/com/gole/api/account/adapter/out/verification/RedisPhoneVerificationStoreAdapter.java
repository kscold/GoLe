package com.gole.api.account.adapter.out.verification;

import com.gole.api.account.application.port.out.PhoneVerificationStorePort;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis 기반 전화번호 인증 코드 저장소. ({@code RedisOAuthStateStoreAdapter}의 TTL 패턴 재사용, onboarding D2)
 *
 * <ul>
 *   <li>{@code phone:otp:{accountId}} → {@code 번호\n저장코드\n오답횟수}, TTL 5분. 저장코드는 기본적으로
 *       {@code PasswordHasherPort}로 해싱한 값이다 — Redis 조회 권한만으로 인증을 가로채지 못하게
 *       한다. 개발 환경 옵트인({@code PhoneVerificationCodeExposurePolicy})에서만 평문이 들어오며,
 *       어느 쪽이든 이 어댑터는 문자열로만 다룬다. BCrypt 해시는 {@code $}, {@code .}, {@code /} 등을
 *       포함할 수 있지만 개행 문자는 절대 만들지 않으므로 이 구분자 포맷은 그대로 유효하다.
 *   <li>{@code phone:otp:cooldown:{accountId}} → 존재하면 재발송 거부, TTL 60초
 *   <li>{@code phone:otp:daily:{accountId}} → 발송 카운터, 최초 발송 기준 24시간
 * </ul>
 */
@Component
public class RedisPhoneVerificationStoreAdapter implements PhoneVerificationStorePort {

    private static final String OTP_PREFIX = "phone:otp:";
    private static final String COOLDOWN_PREFIX = "phone:otp:cooldown:";
    private static final String DAILY_PREFIX = "phone:otp:daily:";

    private final StringRedisTemplate redisTemplate;

    public RedisPhoneVerificationStoreAdapter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean isCooldownActive(String accountId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(COOLDOWN_PREFIX + accountId));
    }

    @Override
    public void startCooldown(String accountId, Duration ttl) {
        redisTemplate.opsForValue().set(COOLDOWN_PREFIX + accountId, "1", ttl);
    }

    @Override
    public long incrementDailySendCount(String accountId, Duration ttl) {
        String key = DAILY_PREFIX + accountId;
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 첫 발송에만 만료를 건다. 매번 갱신하면 창이 계속 밀려 24시간 한도가 무의미해진다.
            redisTemplate.expire(key, ttl);
        }
        return count == null ? 0L : count;
    }

    @Override
    public void issue(String accountId, PhoneVerificationChallenge challenge, Duration ttl) {
        redisTemplate.opsForValue().set(OTP_PREFIX + accountId, serialize(challenge), ttl);
    }

    @Override
    public void recordFailedAttempt(String accountId, PhoneVerificationChallenge challenge) {
        String key = OTP_PREFIX + accountId;
        Long remaining = redisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (remaining == null || remaining <= 0) {
            return; // 이미 만료됐다면 되살리지 않는다.
        }
        // 남은 시간만큼만 다시 건다. 평범한 SET은 TTL을 지워 코드가 영구히 남는다.
        redisTemplate.opsForValue().set(key, serialize(challenge), Duration.ofSeconds(remaining));
    }

    @Override
    public Optional<PhoneVerificationChallenge> find(String accountId) {
        String raw = redisTemplate.opsForValue().get(OTP_PREFIX + accountId);
        if (raw == null) {
            return Optional.empty();
        }
        String[] parts = raw.split("\n", 3);
        if (parts.length != 3) {
            return Optional.empty();
        }
        return Optional.of(new PhoneVerificationChallenge(parts[0], parts[1], Integer.parseInt(parts[2])));
    }

    @Override
    public void delete(String accountId) {
        redisTemplate.delete(OTP_PREFIX + accountId);
    }

    @Override
    public void deleteAllForAccount(String accountId) {
        redisTemplate.delete(
                java.util.List.of(OTP_PREFIX + accountId, COOLDOWN_PREFIX + accountId, DAILY_PREFIX + accountId));
    }

    private static String serialize(PhoneVerificationChallenge challenge) {
        return challenge.phoneNumber() + "\n" + challenge.storedCode() + "\n" + challenge.attempts();
    }
}
