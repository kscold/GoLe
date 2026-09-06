import { test, expect } from "@playwright/test";

// 백엔드 없이 검증 가능한 통합 인증 화면 구성/내비게이션 테스트.
test.describe("Auth navigation", () => {
  test.beforeEach(async ({ page }) => {
    await page.route("**/api/v1/config/launch", (route) =>
      route.fulfill({
        json: {
          stage: 0,
          tradeMode: "DIRECT_CHAT",
          features: { payments: false, reviews: false, partnerPayout: false },
          sellerIdentityVerificationReady: false,
          emailAuthenticationAvailable: true,
          updatedAt: null,
        },
      }),
    );
  });

  test("로그인 화면에 로컬 폼 + 소셜 4종 진입이 보인다", async ({ page }) => {
    await page.goto("/login");
    await expect(page.getByRole("heading", { name: "로그인" })).toBeVisible();
    // 로컬 폼
    await expect(page.getByRole("button", { name: "로그인" })).toBeVisible();
    // 소셜(미설정이면 '준비 중'으로 비활성이지만 버튼 자체는 노출)
    await expect(page.getByRole("button", { name: /Google/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /카카오/ })).toBeVisible();
    await expect(page.getByRole("button", { name: /네이버/ })).toBeVisible();
  });

  test("로그인 ↔ 회원가입 탭으로 전환한다", async ({ page }) => {
    await page.goto("/login");
    await page.getByRole("tab", { name: "회원가입" }).click();
    await expect(page).toHaveURL(/\/signup$/);
    await expect(page.getByRole("button", { name: "이메일로 가입하기" })).toBeVisible();

    await page.getByRole("tab", { name: "로그인" }).click();
    await expect(page).toHaveURL(/\/login$/);
  });

  test("회원가입은 최신 정책을 분리 확인하고 버전 증빙을 함께 보낸다", async ({ page }) => {
    let registerBody: unknown;
    await page.route("**/api/v1/policies/current", async (route) => {
      await route.fulfill({
        status: 200,
        contentType: "application/json",
        body: JSON.stringify({
          termsVersion: "2026-09-04",
          privacyVersion: "2026-09-05",
          thirdPartyProvisionVersion: "2026-09-04",
          minimumAge: 14,
        }),
      });
    });
    await page.route("**/api/v1/accounts", async (route) => {
      registerBody = route.request().postDataJSON();
      await route.fulfill({
        status: 201,
        contentType: "application/json",
        body: JSON.stringify({ accountId: "account-policy" }),
      });
    });

    await page.goto("/signup?returnTo=%2Fcollection");
    const submit = page.getByRole("button", { name: "이메일로 가입하기" });
    await expect(submit).toBeDisabled();
    await expect(page.getByRole("link", { name: "이용약관" })).toHaveAttribute("href", "/terms");
    await expect(page.getByRole("link", { name: "개인정보처리방침", exact: true })).toHaveAttribute(
      "href",
      "/privacy",
    );
    const thirdPartyConsent = page.getByRole("checkbox", {
      name: /개인정보 제3자 제공에 동의합니다/,
    });
    await expect(thirdPartyConsent).not.toBeChecked();
    const notice = page.getByTestId("third-party-provision-notice");
    await expect(notice).toContainText("제공받는 자: 대화방 참여자");
    await expect(notice).toContainText("제공받는 자: 거래 상대방");
    await expect(notice).toContainText("정보주체의 전체 전화번호");
    await expect(notice).toContainText("동의하지 않아도 가입할 수 있으나");

    await page.getByRole("checkbox", { name: /이용약관/ }).check();
    await page.getByRole("checkbox", { name: /개인정보처리방침/ }).check();
    await page.getByRole("checkbox", { name: /만 14세 이상/ }).check();
    await page.getByLabel("이메일").fill("policy@gole.test");
    await page.getByLabel("비밀번호").fill("password1");
    await expect(submit).toBeEnabled();
    await submit.click();

    expect(registerBody).toEqual({
      email: "policy@gole.test",
      password: "password1",
      termsVersion: "2026-09-04",
      privacyVersion: "2026-09-05",
      thirdPartyProvisionVersion: "2026-09-04",
      termsAccepted: true,
      privacyAcknowledged: true,
      thirdPartyProvisionAccepted: false,
      minimumAgeConfirmed: true,
    });
    await expect(page).toHaveURL(/\/verify\?returnTo=%2Fcollection/);
    await expect
      .poll(() =>
        page.evaluate(() => window.sessionStorage.getItem("gole.pending-verification-email")),
      )
      .toBe("policy@gole.test");
  });

  test("소셜 가입도 선택한 제3자 제공 동의와 공지 버전을 OAuth 요청에 보낸다", async ({ page }) => {
    let authorizeBody: Record<string, unknown> | undefined;
    await page.route("**/api/v1/policies/current", (route) =>
      route.fulfill({
        json: {
          termsVersion: "2026-09-04",
          privacyVersion: "2026-09-05",
          thirdPartyProvisionVersion: "third-party-2026-09-04",
          minimumAge: 14,
        },
      }),
    );
    await page.route("**/api/v1/auth/oauth/providers", (route) =>
      route.fulfill({ json: ["google"] }),
    );
    await page.route("**/api/v1/auth/oauth/google/authorize-url", (route) => {
      authorizeBody = route.request().postDataJSON() as Record<string, unknown>;
      return route.fulfill({ json: { url: "/auth/callback/google?code=test&state=test" } });
    });

    await page.goto("/signup");
    await page.getByRole("checkbox", { name: /이용약관/ }).check();
    await page.getByRole("checkbox", { name: /개인정보처리방침/ }).check();
    await page.getByRole("checkbox", { name: /만 14세 이상/ }).check();
    await page.getByRole("checkbox", { name: /개인정보 제3자 제공에 동의합니다/ }).check();
    await page.getByRole("button", { name: "Google로 가입" }).click();

    await expect
      .poll(() => authorizeBody?.thirdPartyProvisionVersion)
      .toBe("third-party-2026-09-04");
    expect(authorizeBody?.thirdPartyProvisionAccepted).toBe(true);
    expect(authorizeBody?.minimumAgeConfirmed).toBe(true);
  });

  test("인증 화면에서 뒤로가기 버튼이 보인다", async ({ page }) => {
    const response = await page.goto("/login");
    await expect(page.getByRole("button", { name: "뒤로 가기" })).toBeVisible();
    expect(response?.headers()["x-content-type-options"]).toBe("nosniff");
    expect(response?.headers()["x-frame-options"]).toBe("DENY");
    expect(response?.headers()["content-security-policy"]).toContain("frame-ancestors 'none'");
  });

  test("이메일 인증 화면이 렌더된다", async ({ page }) => {
    await page.addInitScript(() => {
      window.sessionStorage.setItem("gole.pending-verification-email", "tester@gole.com");
    });
    await page.goto("/verify");
    await expect(page.getByRole("heading", { name: "이메일 인증" })).toBeVisible();
    await expect(page.getByRole("button", { name: "인증하기" })).toBeVisible();
    await expect(page.getByRole("button", { name: /인증 코드 다시 받기/ })).toBeDisabled();
    await expect(page).toHaveURL("/verify");
  });

  test("과거 인증 이메일 query는 저장하거나 폼에 복원하지 않고 URL에서 제거한다", async ({
    page,
  }) => {
    await page.goto("/verify?email=legacy%40gole.test");

    await expect(page).toHaveURL("/verify");
    await expect(page.getByLabel("이메일")).toHaveValue("");
    await expect
      .poll(() =>
        page.evaluate(() => window.sessionStorage.getItem("gole.pending-verification-email")),
      )
      .toBeNull();
  });

  test("미인증 로그인은 이메일 인증 화면으로 복구할 수 있다", async ({ page }) => {
    await page.route("**/api/v1/accounts/sessions", async (route) => {
      await route.fulfill({
        status: 403,
        contentType: "application/json",
        body: JSON.stringify({
          code: "ACCOUNT_NOT_VERIFIED",
          message: "이메일 인증을 완료해 주세요",
        }),
      });
    });
    await page.goto("/login");
    await page.getByLabel("이메일").fill("pending@gole.com");
    await page.getByLabel("비밀번호").fill("password1");
    await page.getByRole("button", { name: "로그인" }).click();

    const recovery = page.getByRole("link", { name: "이메일 인증하러 가기" });
    await expect(recovery).toBeVisible();
    await expect(recovery).toHaveAttribute("href", "/verify");
    await recovery.click();
    await expect(page.getByLabel("이메일")).toHaveValue("pending@gole.com");
  });

  test("메일 발송이 준비 전이면 이메일 challenge만 닫고 기존 로그인을 유지한다", async ({
    page,
  }) => {
    await page.route("**/api/v1/policies/current", (route) =>
      route.fulfill({
        json: {
          termsVersion: "2026-09-04",
          privacyVersion: "2026-09-05",
          thirdPartyProvisionVersion: "2026-09-04",
          minimumAge: 14,
        },
      }),
    );
    await page.route("**/api/v1/config/launch", (route) =>
      route.fulfill({
        json: {
          stage: 0,
          tradeMode: "DIRECT_CHAT",
          features: { payments: false, reviews: false, partnerPayout: false },
          sellerIdentityVerificationReady: false,
          emailAuthenticationAvailable: false,
          updatedAt: null,
        },
      }),
    );

    await page.goto("/login");
    await expect(page.getByRole("button", { name: "로그인", exact: true })).toBeVisible();
    await expect(page.getByRole("link", { name: "비밀번호를 잊으셨나요?" })).toHaveCount(0);

    await page.getByRole("tab", { name: "회원가입" }).click();
    await expect(page.getByText("이메일 회원가입을 준비하고 있어요.")).toBeVisible();
    await expect(page.getByRole("button", { name: "이메일로 가입하기" })).toHaveCount(0);
    await expect(page.getByRole("checkbox", { name: /이용약관/ })).toBeVisible();
  });

  test("메일 발송이 준비 전이면 미인증 로그인 복구 링크도 노출하지 않는다", async ({ page }) => {
    await page.route("**/api/v1/config/launch", (route) =>
      route.fulfill({
        json: {
          stage: 0,
          tradeMode: "DIRECT_CHAT",
          features: { payments: false, reviews: false, partnerPayout: false },
          sellerIdentityVerificationReady: false,
          emailAuthenticationAvailable: false,
          updatedAt: null,
        },
      }),
    );
    await page.route("**/api/v1/accounts/sessions", async (route) => {
      await route.fulfill({
        status: 403,
        contentType: "application/json",
        body: JSON.stringify({
          code: "ACCOUNT_NOT_VERIFIED",
          message: "이메일 인증을 완료해 주세요",
        }),
      });
    });
    await page.goto("/login");
    await page.getByLabel("이메일").fill("pending@gole.com");
    await page.getByLabel("비밀번호").fill("password1");
    await page.getByRole("button", { name: "로그인" }).click();

    await expect(
      page.getByRole("alert").filter({ hasText: "이메일 인증을 완료해 주세요" }),
    ).toBeVisible();
    await expect(page.getByRole("link", { name: "이메일 인증하러 가기" })).toHaveCount(0);
  });

  test("메일 발송이 준비 전이면 인증 코드 화면에 직접 들어가도 닫혀 있다", async ({ page }) => {
    await page.route("**/api/v1/config/launch", (route) =>
      route.fulfill({
        json: {
          stage: 0,
          tradeMode: "DIRECT_CHAT",
          features: { payments: false, reviews: false, partnerPayout: false },
          sellerIdentityVerificationReady: false,
          emailAuthenticationAvailable: false,
          updatedAt: null,
        },
      }),
    );

    await page.goto("/verify");

    await expect(page.getByText("이메일 인증 코드 발송을 준비하고 있어요.")).toBeVisible();
    await expect(page.getByRole("button", { name: "인증하기" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "로그인으로 돌아가기" })).toHaveAttribute(
      "href",
      "/login",
    );
  });

  test("메일 발송이 준비 전이면 비밀번호 재설정 화면에 직접 들어가도 닫혀 있다", async ({
    page,
  }) => {
    await page.route("**/api/v1/config/launch", (route) =>
      route.fulfill({
        json: {
          stage: 0,
          tradeMode: "DIRECT_CHAT",
          features: { payments: false, reviews: false, partnerPayout: false },
          sellerIdentityVerificationReady: false,
          emailAuthenticationAvailable: false,
          updatedAt: null,
        },
      }),
    );

    await page.goto("/forgot-password?returnTo=%2Fcollection");

    await expect(page.getByText("이메일 재설정 코드 발송을 준비하고 있어요.")).toBeVisible();
    await expect(page.getByRole("button", { name: "재설정 코드 받기" })).toHaveCount(0);
    await expect(page.getByRole("link", { name: "로그인으로 돌아가기" })).toHaveAttribute(
      "href",
      "/login?returnTo=%2Fcollection",
    );
  });

  test("잘못된 로그인 자격증명은 이미 저장된 세션 메타데이터를 지우지 않는다", async ({ page }) => {
    await page.route("**/api/v1/accounts/sessions", async (route) => {
      await route.fulfill({
        status: 401,
        contentType: "application/json",
        body: JSON.stringify({
          code: "INVALID_CREDENTIALS",
          message: "이메일 또는 비밀번호가 올바르지 않습니다",
        }),
      });
    });
    await page.goto("/login");
    await page.evaluate(() => {
      window.localStorage.setItem(
        "gole.session",
        JSON.stringify({ accountId: "account-existing", sessionToken: "", role: "USER" }),
      );
    });

    await page.getByLabel("이메일").fill("member@gole.com");
    await page.getByLabel("비밀번호").fill("wrong-password");
    await page.getByRole("button", { name: "로그인" }).click();

    await expect(page.getByRole("alert").filter({ hasText: "올바르지 않습니다" })).toBeVisible();
    await expect
      .poll(() => page.evaluate(() => window.localStorage.getItem("gole.session")))
      .not.toBeNull();
  });

  test("비밀번호 재설정은 계정 존재를 노출하지 않고 원래 복귀 경로를 보존한다", async ({
    page,
  }) => {
    let requestBody: unknown;
    let confirmationBody: unknown;
    await page.route("**/api/v1/accounts/password-reset", async (route) => {
      requestBody = route.request().postDataJSON();
      await route.fulfill({ status: 204 });
    });
    await page.route("**/api/v1/accounts/password-reset/confirmation", async (route) => {
      confirmationBody = route.request().postDataJSON();
      await route.fulfill({ status: 204 });
    });

    await page.goto("/login?returnTo=%2Fcollection");
    await page.getByRole("link", { name: "비밀번호를 잊으셨나요?" }).click();
    await expect(page).toHaveURL(/\/forgot-password\?returnTo=%2Fcollection/);

    await page.getByLabel("이메일").fill("member@gole.test");
    await page.getByRole("button", { name: "재설정 코드 받기" }).click();
    await expect(page.getByRole("status")).toContainText("가입된 이메일이라면");
    expect(requestBody).toEqual({ email: "member@gole.test" });

    await page.getByLabel("재설정 코드").fill("123456");
    await page.getByLabel("새 비밀번호", { exact: true }).fill("new-password");
    await page.getByLabel("새 비밀번호 확인").fill("new-password");
    await page.getByRole("button", { name: "비밀번호 바꾸기" }).click();

    expect(confirmationBody).toEqual({
      email: "member@gole.test",
      code: "123456",
      newPassword: "new-password",
    });
    await expect(page).toHaveURL(/\/login\?passwordReset=1&returnTo=%2Fcollection/);
    await expect(
      page.getByRole("status").filter({ hasText: "비밀번호가 변경됐어요" }),
    ).toBeVisible();
  });
});
