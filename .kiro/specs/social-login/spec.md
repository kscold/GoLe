# Social Login (OAuth2) — Spec

> 구글/카카오/네이버 OAuth2 소셜 로그인. **client-id/secret 등 토큰은 env 플레이스홀더로 외부화**하여, 나중에 환경변수만 주입하면 즉시 동작한다. 토큰 미설정 provider는 비활성(앱은 정상 부팅).

## Requirements (EARS)
- S1 시스템은 Google·Kakao·Naver OAuth2 Authorization Code 플로우를 지원해야 한다.
- S2 IF provider의 client-id가 비어 있으면(env 미주입), 해당 provider는 비활성으로 간주하고 `GET /providers` 목록에서 제외하며, authorize-url/callback 요청은 400 `OAUTH_PROVIDER_NOT_CONFIGURED`로 거부해야 한다(앱 부팅은 영향 없음).
- S3 WHEN 프론트가 `GET /api/v1/auth/oauth/providers`를 호출하면, 시스템은 설정된(활성) provider 목록을 반환해야 한다.
- S4 WHEN `POST /api/v1/auth/oauth/{provider}/authorize-url` (JSON 본문 `redirectUri`, 선택적 회원가입 정책 동의값·`returnTo`)를 호출하면, 시스템은 provider 동의 화면 URL을 반환해야 한다. state는 서버가 발급하며, 정책 동의값은 access log에 남지 않도록 쿼리스트링이 아닌 요청 본문으로만 받는다.
- S5 WHEN `POST /api/v1/auth/oauth/{provider}/callback {code, redirectUri, state}`를 호출하면, 시스템은 state를 검증한 뒤 code로 provider 토큰을 교환하고 프로필(email, providerId, emailVerified)을 조회해야 한다.
- S6 WHEN 프로필 이메일로 기존 계정이 있으면 그 계정으로, 없으면 새 계정(VERIFIED·USER·임의 비밀번호)을 생성하여 로그인 처리(find-or-create)하고, 기존과 동일한 불투명 세션 토큰을 발급해야 한다.
- S7 IF provider가 이메일을 제공하지 않으면, 시스템은 400 `OAUTH_EMAIL_UNAVAILABLE`로 거부해야 한다.
- S8 IF provider 식별자가 google/kakao/naver가 아니면, 400으로 거부해야 한다.
- S9 프론트는 활성 provider 버튼을 보여주고, 콜백 페이지에서 code/state를 받아 세션을 저장한 뒤 홈(또는 서버가 검증한 복귀 경로)으로 이동해야 한다. CSRF 방지를 위한 state는 서버가 발급·검증하므로 프론트는 콜백에서 받은 값을 그대로 전달만 한다.
- S10 IF provider 프로필의 이메일이 인증되지 않았으면(`email_verified`/`is_email_verified` 등이 false), 시스템은 400 `OAUTH_EMAIL_UNVERIFIED`로 거부해야 한다.
- S11 authorize-url 요청에 회원가입 정책 동의값이 포함되면, 시스템은 기존 회원가입과 동일한 정책 버전·필수 항목 검증을 거쳐야 하고, 신규 계정 생성 시 그 동의를 함께 기록해야 한다.
- S12 WHEN 구글 신규가입이면, 시스템은 콜백 응답의 `onboardingRequired`를 true로 반환해 온보딩 플로우로 보내야 한다. 카카오·네이버 신규가입은 기존 동작(즉시 로그인)을 유지한다.
- S13 시스템은 OAuth 인가 요청에 대해 클라이언트별(버스트·시간당)·전역(버스트·일간) 레이트리밋을 적용해야 한다.
- S14 authorize-url 요청에 `returnTo`가 포함되면, 시스템은 이를 검증·정제해 state에 결박하고 콜백 응답에 그대로 돌려줘야 한다. 안전하지 않은 값(절대 URL, `/login`·`/signup`·`/verify`·`/auth`·`/onboarding` 하위 경로 등)은 폐기한다.
- S15 시스템은 OAuth code가 돌아올 redirectUri를 서버의 정확한 허용목록과 대조해야 하며, 목록에 없으면 400 `OAUTH_REDIRECT_URI_INVALID`로 거부해야 한다. 운영 환경에서는 이 허용목록이 apex 콜백 3종(`https://gole.co.kr/auth/callback/{google,kakao,naver}`)과 정확히 일치하지 않으면 앱 기동 자체가 실패해야 한다.

## Design
- 백엔드(account 컨텍스트, 헥사고날):
  - domain `AuthProvider`(GOOGLE/KAKAO/NAVER).
  - port-in `SocialLoginUseCase`: `enabledProviders()`, `authorizeUrl(provider, redirectUri, signupPolicyAcceptance, returnTo) -> AuthorizeUrlResult{url, state}`, `login(SocialLoginCommand{provider, code, redirectUri, state}) -> SocialLoginResult{accountId, sessionToken, role, newAccount, onboardingRequired, returnTo}`.
  - port-out `SocialIdentityProviderPort`: `isConfigured(provider)`, `authorizeUrl(...)`, `fetchProfile(provider, code, redirectUri) -> SocialProfile{provider, providerId, email, emailVerified}`.
  - port-out `OAuthStateStorePort`: `save(state, OAuthStateContext{provider, redirectUri, signupPolicyAcceptance, returnTo}, ttl)`, `consume(state) -> Optional<OAuthStateContext>`(1회 소비).
  - service `SocialAuthService`: `authorizeUrl`에서 리다이렉트 URI 허용목록 검증(`OAuthRedirectUriPolicy`) → (있으면) 정책 동의 검증 → 서버가 state를 발급해 `OAuthStateStorePort`에 저장 → provider 포트로 동의 화면 URL 생성. `login`에서 state를 1회 소비해 provider/redirectUri 일치 검증(CSRF) → 리다이렉트 URI 재검증 → provider 포트로 프로필 취득 → 이메일 없음/미인증이면 거부 → `AccountRepositoryPort.findByEmail` or `SocialAccountProvisioner.provision`(임의 비밀번호 해시로 신규 계정 생성 + 정책 동의 기록, 동일 Mongo 트랜잭션) → 정지 계정 거부(`ensureNotSuspended`) → `SessionTokenPort.issue` + `SessionStorePort.store`(기존 로그인과 동일 TTL) → 구글 신규가입만 `onboardingRequired` 판정. **Account 애그리거트/암호 정책 무수정**.
  - `OAuthRedirectUriPolicy`: `gole.oauth.allowed-redirect-uris`(콤마 구분)로 구성한 정확한 URI 허용목록. `gole.environment`가 로컬/개발 계열(`local`/`development`/`dev`/`test`/`e2e`)이 아니면 허용목록이 apex 콜백 3종과 정확히 일치해야 하며, 아니면 기동 시 `IllegalStateException`으로 실패한다.
  - `OAuthTransactionCookie`: `authorizeUrl` 응답 시 발급한 state를 `gole_oauth_transaction`(HttpOnly, `SameSite=Lax`, path `/api/v1/auth/oauth`) 쿠키로 요청 브라우저에 결박. 콜백에서 요청 본문의 state와 상수시간(`MessageDigest.isEqual`) 비교해 일치해야만 진행하고, 성공·실패 무관하게 1회 사용 후 쿠키를 지운다. 서버측 Redis state 검증과 별개의 CSRF 이중 방어다.
  - `OAuthReturnTo`: `returnTo`를 동일 오리진 상대경로로만 허용(절대 URL·`\`·dot-segment·제어문자·`/login`·`/signup`·`/verify`·`/auth`·`/onboarding` 하위 경로 거부)하고 정제된 값만 state에 결박.
  - `SocialAccountProvisioner`: 신규 계정 생성과 정책 동의 기록을 `REQUIRES_NEW` 트랜잭션으로 묶는다.
  - adapter-out `OAuthProperties`(@ConfigurationProperties `oauth`) + `RestClientSocialIdentityProviderAdapter`(Spring `RestClient`로 token POST + userinfo GET, provider별 프로필·`emailVerified` 파싱) + `RedisOAuthStateStoreAdapter`(`oauth:state:<state>` 키, get-and-delete로 1회 소비).
  - OAuth 전용 레이트리밋: `PublicAuthRequestLimitUseCase.acquireOAuthAuthorization`이 클라이언트 버스트/시간당 + 전역 버스트/일간 4개 버킷을 검사한다(설정은 아래 표).
  - adapter-in `SocialAuthController` `/api/v1/auth/oauth`.
- 프론트(FSD, `@gole/core/user`로 웹·앱 공유):
  - `fetchSocialProviders()`, `fetchSocialAuthorizeUrl(provider, redirectUri, signupPolicyAcceptance?, returnTo?)`(POST + JSON 본문), `socialCallback(provider, code, redirectUri, state) -> SocialCallbackResult{session, newAccount, returnTo}`.
  - `features/social-login`: 활성 provider 버튼 → authorize-url을 받아 리다이렉트(state는 서버가 발급해 쿠키로 결박하므로 프론트는 별도 생성·저장을 하지 않음).
  - `views/oauth-callback` + app route `/auth/callback/[provider]`: code/state를 그대로 `socialCallback`에 전달 → `saveSession` → `onboardingRequired`면 온보딩, `newAccount`면 환영 화면, 아니면 `returnTo`(관리자 경로는 ADMIN만) 또는 홈으로 이동.
  - 로그인 화면(sign-in)에 버튼 노출.

### 에러 코드
`OAUTH_PROVIDER_NOT_CONFIGURED`, `OAUTH_PROVIDER_UNSUPPORTED`, `OAUTH_STATE_INVALID`,
`OAUTH_EMAIL_UNAVAILABLE`, `OAUTH_EMAIL_UNVERIFIED`, `OAUTH_EXCHANGE_FAILED`,
`OAUTH_PROFILE_FAILED`, `OAUTH_REDIRECT_URI_INVALID`. provider 오류 응답 원문은 로그·응답에
되비추지 않고 위 코드로만 노출한다.

### OAuth 레이트리밋(application.yml)
클라이언트 버스트(`GOLE_AUTH_OAUTH_CLIENT_BURST_*`, 기본 1분당 20회) · 클라이언트 시간당
(`GOLE_AUTH_OAUTH_CLIENT_HOURLY_*`, 기본 시간당 120회) · 전역 버스트(`GOLE_AUTH_OAUTH_GLOBAL_BURST_*`,
기본 1분당 120회) · 전역 일간(`GOLE_AUTH_OAUTH_GLOBAL_DAILY_*`, 기본 일 2000회) 4개 버킷.

### 설정(application.yml, env 플레이스홀더)
```
oauth:
  providers:
    google:  { client-id: ${GOOGLE_OAUTH_CLIENT_ID:},  client-secret: ${GOOGLE_OAUTH_CLIENT_SECRET:},  ... }
    kakao:   { client-id: ${KAKAO_OAUTH_CLIENT_ID:},   client-secret: ${KAKAO_OAUTH_CLIENT_SECRET:},   ... }
    naver:   { client-id: ${NAVER_OAUTH_CLIENT_ID:},   client-secret: ${NAVER_OAUTH_CLIENT_SECRET:},   ... }
```
authorization-uri/token-uri/user-info-uri/scope는 provider별 기본값을 두고 env로 덮어쓸 수 있다. **client-id/secret만 나중에 주입하면 동작.**

리다이렉트 URI 허용목록은 `gole.oauth.allowed-redirect-uris`(콤마 구분)로 별도 관리한다. 기본값은
로컬 개발용(`http://localhost:3000·3010/auth/callback/{google,kakao,naver}`)이고, 운영 환경에서는
`GOLE_OAUTH_ALLOWED_REDIRECT_URIS`로 apex 콜백 3종만 주입해야 한다(그 외 값이면 기동 실패).

## Tasks
- [x] B1 AuthProvider, SocialLoginUseCase, SocialIdentityProviderPort, SocialProfile
- [x] B2 SocialAuthService (find-or-create + 세션 발급)
- [x] B3 OAuthProperties + RestClientSocialIdentityProviderAdapter + application.yml
- [x] B4 SocialAuthController (providers/authorize-url/callback)
- [x] B5 SocialAuthServiceTest
- [x] F1 entities/user social API
- [x] F2 features/social-login 버튼
- [x] F3 views/oauth-callback + app route + sign-in 연동
- [ ] D1 빌드·배포·스모크

## 보안/후속
- state는 서버가 발급·Redis 저장·콜백 1회 소비로 검증하고(CSRF), `gole_oauth_transaction` HttpOnly
  쿠키로 브라우저에도 결박해 상수시간 비교로 이중 방어한다(`OAuthTransactionCookie`). provider별
  이메일 미인증도 `OAUTH_EMAIL_UNVERIFIED`로 거부한다. 둘 다 구현 완료.
- 이메일 기준 find-or-create는 동일 이메일=동일 사용자로 간주(소셜↔로컬 통합). 후속에 명시적 계정 연결 UX 가능.
- 후속(미구현): PKCE, 계정-소셜 연결(provider/providerId 영속 — 현재는 프로필 조회 시 providerId를
  얻지만 Account에 저장하지 않는다).
