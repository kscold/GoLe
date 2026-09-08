package com.gole.api.account.application.port.in;

import com.gole.api.account.domain.model.Role;
import java.util.Optional;

/**
 * Inbound port: 세션 토큰으로 현재 로그인 사용자를 해석한다. (/api/v1/accounts/me)
 */
public interface GetCurrentSessionUseCase {

    Optional<CurrentSession> resolve(String sessionToken);

    /**
     * @param onboardingRequired 아직 온보딩을 요구해야 하는가. (onboarding R8)
     *     세션 해석이 이미 계정을 읽으므로 여기서 함께 실어 보낸다 — {@code /me}마다 계정을
     *     한 번 더 조회하지 않기 위해서다.
     * @param nickname 온보딩에서 설정한 표시 이름. 아직 설정 전이면 {@code null}. 같은 이유로
     *     세션 해석에 편승해 실어 보낸다.
     */
    record CurrentSession(String accountId, String email, Role role, boolean onboardingRequired, String nickname) {

        /** 온보딩 판정이 필요 없는 호출부(권한 가드 등)를 위한 축약 생성자. */
        public CurrentSession(String accountId, String email, Role role) {
            this(accountId, email, role, false, null);
        }
    }
}
