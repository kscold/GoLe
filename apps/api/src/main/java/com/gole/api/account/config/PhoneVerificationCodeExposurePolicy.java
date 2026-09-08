package com.gole.api.account.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 전화번호 인증 코드(OTP)를 개발자가 직접 읽을 수 있게 열어 둘지 결정한다. (onboarding D2)
 *
 * <p>기본값은 닫힘이다. OTP는 단방향 해시로만 저장하므로 Redis 조회 권한만으로는 인증을
 * 가로챌 수 없다. 문제는 실제 알림톡이 없는 로컬에서 그러면 코드를 얻을 경로가 사라져
 * 전화 인증 흐름을 손으로 밟아볼 수 없다는 것이다. 그 예외를 여는 열쇠를 여기 한 곳에 모은다.
 *
 * <p>발송 수단 설정({@code coolsms.enabled})으로 판단하지 않는다. 그 값의 뜻은 "SMS 공급자
 * 자격증명이 있는가"이지 "평문을 남겨도 되는 환경인가"가 아니다. 둘은 지금 우연히 겹칠 뿐이라,
 * 운영에서 사고 대응으로 발송만 잠시 끄는 조작이 저장 방식까지 바꿔 버리면 안 된다.
 */
@Component
public class PhoneVerificationCodeExposurePolicy {

    private static final Set<String> DEVELOPER_ENVIRONMENTS = Set.of("local", "development", "dev", "test", "e2e");

    private final boolean plaintextAllowed;

    public PhoneVerificationCodeExposurePolicy(
            @Value("${gole.environment:local}") String environment,
            @Value("${gole.onboarding.log-verification-codes:false}") boolean codeExposureEnabled) {
        String normalized = environment == null ? "" : environment.trim().toLowerCase(Locale.ROOT);
        // 환경과 옵트인을 모두 요구한다. 환경만 보면 운영에 GOLE_ENVIRONMENT를 잘못 넣는 순간
        // 뚫리고, 플래그만 보면 운영에서 실수로 켜는 순간 뚫린다. 둘을 곱해야 사고 하나로는
        // 열리지 않는다.
        this.plaintextAllowed = codeExposureEnabled && DEVELOPER_ENVIRONMENTS.contains(normalized);
    }

    /**
     * true면 OTP를 평문으로 저장하고 로그에도 원문을 남긴다. <b>개발 편의 전용</b>이며 공개 환경에서는
     * 어떤 설정 조합으로도 true가 되지 않는다.
     */
    public boolean plaintextAllowed() {
        return plaintextAllowed;
    }
}
