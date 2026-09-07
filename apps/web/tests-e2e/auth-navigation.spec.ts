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
    // 출처 없이 직접 들어온 진입은 방금 나간 코드가 없다. 기다리게 하지 않는다.
    await expect(page.getByRole("button", { name: "인증 코드 다시 받기" })).toBeEnabled();
    await expect(page).toHaveURL("/verify");
  });

  test("인증 화면은 코드가 오지 않는 계정에게 상시 출구를 보여준다", async ({ page }) => {
    await page.goto("/verify?returnTo=%2Fcollection");

    await expect(page.getByText("이미 가입을 마친 계정일 수 있어요.")).toBeVisible();
    await expect(page.getByRole("link", { name: "로그인하기" })).toHaveAttribute(
      "href",
      "/login?returnTo=%2Fcollection",
    );
    await expect(page.getByRole("link", { name: "비밀번호 찾기" })).toHaveAttribute(
      "href",
      "/forgot-password?returnTo=%2Fcollection",
    );
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

  test("미인증 로그인은 인증 화면으로 바로 넘어가며 코드를 한 번 발송한다", async ({ page }) => {
    let resendCount = 0;
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
    await page.route("**/api/v1/accounts/verification/resend", async (route) => {
      resendCount += 1;
      await route.fulfill({ status: 204, body: "" });
    });

    await page.goto("/login");
    await page.getByLabel("이메일").fill("pending@gole.com");
    await page.getByLabel("비밀번호").fill("password1");
    await page.getByRole("button", { name: "로그인" }).click();

    // 오류 상자에 머무르지 않고 다음 단계로 넘어간다.
    await expect(page).toHaveURL("/verify");
    await expect(page.getByLabel("이메일")).toHaveValue("pending@gole.com");
    await expect.poll(() => resendCount).toBe(1);
    await expect(page.getByRole("button", { name: /초 후 인증 코드 다시 받기/ })).toBeDisabled();
    await expect(page.getByText("인증이 필요한 계정이면 코드를 보내드립니다.")).toBeVisible();

    // 1회용 출처 마커를 소비했으므로 새로고침해도 다시 보내지 않는다.
    await page.reload();
    await expect(page.getByRole("button", { name: "인증하기" })).toBeVisible();
    await expect.poll(() => resendCount).toBe(1);
  });

  test("가입 직후 인증 화면은 코드를 다시 발송하지 않는다", async ({ page }) => {
    let resendCount = 0;
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
    await page.route("**/api/v1/accounts/verification/resend", async (route) => {
      resendCount += 1;
      await route.fulfill({ status: 204, body: "" });
    });
    await page.route("**/api/v1/accounts", (route) =>
      route.fulfill({ status: 201, json: { accountId: "registration-pending" } }),
    );

    await page.goto("/signup");
    await page.getByRole("checkbox", { name: /이용약관/ }).check();
    await page.getByRole("checkbox", { name: /개인정보처리방침/ }).check();
    await page.getByRole("checkbox", { name: /만 14세 이상/ }).check();
    await page.getByLabel("이메일").fill("fresh@gole.test");
    await page.getByLabel("비밀번호").fill("password1");
    await page.getByRole("button", { name: "이메일로 가입하기" }).click();

    await expect(page).toHaveURL("/verify");
    // register가 이미 보냈다. 여기서 또 보내면 수신자 일일 한도만 태운다.
    await expect(page.getByRole("button", { name: /초 후 인증 코드 다시 받기/ })).toBeDisabled();
    expect(resendCount).toBe(0);
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

  test("메일 발송이 준비 전이면 미인증 로그인을 인증 화면으로 보내지 않는다", async ({ page }) => {
    let resendCount = 0;
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
    await page.route("**/api/v1/accounts/verification/resend", async (route) => {
      resendCount += 1;
      await route.fulfill({ status: 204, body: "" });
    });
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
    await expect(page).toHaveURL("/login");
    expect(resendCount).toBe(0);
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
