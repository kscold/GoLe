import { test, expect, type Page } from "@playwright/test";

const e2eBaseUrl = process.env.E2E_BASE_URL;
const isRemoteTarget =
  e2eBaseUrl !== undefined &&
  !/^https?:\/\/(?:localhost|127\.0\.0\.1)(?::\d+)?(?:\/|$)/.test(e2eBaseUrl);

/**
 * 내 정보 화면이 실제로 데이터를 채우는지 본다.
 *
 * 이 화면은 "영구히 불러오는 중"으로 조용히 멈춘 적이 있다. 세 요청이 아예 나가지 않았는데
 * 화면에는 스켈레톤과 "불러오는 중..."만 있어서 오류로도 보이지 않았다. mobile.spec은 이
 * 경로를 방문하지만 가로 스크롤만 재기 때문에 아무것도 안 뜬 화면에서도 통과한다.
 *
 * <b>세션을 `saveSession`이 저장하는 모양 그대로 심는 것이 이 파일의 핵심이다.</b>
 * 앱은 토큰을 로컬 저장소에 두지 않고(인증은 HttpOnly 쿠키) 빈 문자열로 저장한다. 반면
 * support/e2e-session의 signInAs는 실제 토큰을 채워 넣으므로, 그걸 쓰면 "토큰이 비었다"는
 * 조건 자체가 재현되지 않아 회귀를 놓친다. 실제로 그렇게 짰다가 버그를 되살려도 통과했다.
 */

/** 앱이 새로고침 이후 실제로 갖고 있는 세션: 토큰은 빈 문자열이다. */
async function seedCookieSession(page: Page): Promise<void> {
  await page.addInitScript(() => {
    window.localStorage.setItem(
      "gole.session",
      JSON.stringify({ accountId: "acc-1", sessionToken: "", role: "USER" }),
    );
  });
}

/** 쿠키로 인증되는 정상 응답을 흉내 낸다. 백엔드 없이 화면 계약만 본다. */
async function mockProfileApis(page: Page): Promise<void> {
  // 헤더 폴링도 같은 인증 클라이언트를 쓴다. 이 응답을 실백엔드 401에 맡기면 프로필 mock이
  // 정상이어도 전역 stale-session 정리가 먼저 실행되어 테스트가 로그인 화면으로 바뀐다.
  await page.route("**/api/v1/users/acc-1/notifications/unread-count", (route) =>
    route.fulfill({ json: { unreadCount: 0 } }),
  );
  await page.route("**/api/v1/accounts/me", (route) =>
    route.fulfill({
      json: { accountId: "acc-1", email: "seller@gole.test", role: "USER", nickname: "레고매니아" },
    }),
  );
  // (main) 레이아웃의 OnboardingBanner도 같은 이유로 격리한다 — 목킹 안 하면 실제
  // 백엔드 401이 위 알림 폴링과 똑같이 전역 stale-session 정리를 먼저 실행시킨다.
  await page.route("**/api/v1/accounts/me/onboarding", (route) =>
    route.fulfill({
      json: {
        required: false,
        legacyExempt: true,
        nicknameCompleted: true,
        nickname: "e2e",
        phoneCompleted: true,
        maskedPhoneNumber: "010-****-0000",
        interestTagsCompleted: true,
        interestTags: [],
        privacyConsented: true,
        marketingConsented: false,
      },
    }),
  );
  await page.route("**/api/v1/accounts/me/third-party-provision-consents/current", (route) =>
    route.fulfill({
      json: { noticeVersion: "2026-09-04", consented: false, lastDecisionAt: null },
    }),
  );
  await page.route("**/api/v1/config/launch", (route) =>
    route.fulfill({
      json: {
        stage: 2,
        tradeMode: "MANUAL_SETTLEMENT",
        features: { payments: true, reviews: true, partnerPayout: false },
        sellerIdentityVerificationReady: true,
        emailAuthenticationAvailable: true,
        updatedAt: "2026-09-03T00:00:00Z",
      },
    }),
  );
  await page.route("**/api/v1/orders?buyerId=**", (route) =>
    route.fulfill({
      json: [{ id: "ORD-PROFILE-1", status: "completed", amount: 280000 }],
    }),
  );
  await page.route("**/api/v1/orders/sales", (route) =>
    route.fulfill({
      json: [{ id: "ORD-SALE-1", status: "funds_held", amount: 310000 }],
    }),
  );
  await page.route("**/api/v1/orders/settlements", (route) =>
    route.fulfill({
      json: [
        {
          orderId: "ORD-SALE-1",
          grossAmount: 310000,
          fee: 15500,
          payout: 294500,
          feeRate: 0.05,
          status: "PENDING",
          createdAt: "2026-09-02T00:00:00Z",
          payableAt: "2026-09-05T00:00:00Z",
          paidAt: null,
          payoutNextAttemptAt: null,
        },
      ],
    }),
  );
  await page.route("**/api/v1/listings/mine**", (route) =>
    route.fulfill({
      json: [
        {
          id: "listing-active-1",
          sellerId: "acc-1",
          title: "밀레니엄 팰컨 75192",
          price: 980000,
          status: "active",
          photoUrls: [],
        },
        {
          id: "listing-sold-1",
          sellerId: "acc-1",
          title: "에펠탑 10307",
          price: 280000,
          status: "sold",
          photoUrls: [],
        },
      ],
    }),
  );
}

test.describe("내 정보", () => {
  test.skip(isRemoteTarget, "응답 가로채기 기반 — 로컬 프론트 전용");

  test.beforeEach(async ({ page }) => {
    await seedCookieSession(page);
    await mockProfileApis(page);
  });

  test("빈 토큰 세션에서도 내 정보가 로딩 상태에 멈추지 않고 채워진다", async ({ page }) => {
    await page.goto("/profile");

    // UUID 조각이 아니라 사람이 알아보는 이름이 보여야 한다.
    await expect(page.getByRole("heading", { name: "레고매니아" })).toBeVisible();
    await expect(page.getByText("불러오는 중")).toHaveCount(0);
    await expect(page.getByText("불러오지 못했어요")).toHaveCount(0);
  });

  // 온보딩에서 닉네임을 정하고도 프로필 어디에도 안 보이던 회귀를 막는다. /me가 닉네임을
  // 아예 내려주지 않아 화면이 이메일을 제목으로 쓰고 있었다.
  test("닉네임을 설정한 계정은 제목과 내 정보 항목에 닉네임을 보여준다", async ({ page }) => {
    await page.goto("/profile");

    await expect(page.getByRole("heading", { name: "레고매니아" })).toBeVisible();
    // 이메일이 사라지면 안 된다 — 자기 계정을 식별하는 값이다.
    await expect(page.getByText("seller@gole.test")).toBeVisible();
    await expect(page.getByText("아직 설정하지 않았어요")).toHaveCount(0);
  });

  test("닉네임이 없으면 빈 줄 대신 설정하러 갈 링크를 준다", async ({ page }) => {
    // 서버가 필드를 생략하는 경우(undefined)도 "아직 없음"으로 읽어야 한다.
    await page.route("**/api/v1/accounts/me", (route) =>
      route.fulfill({ json: { accountId: "acc-1", email: "seller@gole.test", role: "USER" } }),
    );

    await page.goto("/profile");

    await expect(page.getByText("아직 설정하지 않았어요")).toBeVisible();
    await expect(page.getByRole("link", { name: "닉네임 설정하기" })).toHaveAttribute(
      "href",
      "/onboarding",
    );
    // 닉네임이 없을 때는 이메일이 제목 자리를 대신한다.
    await expect(page.getByRole("heading", { name: "seller@gole.test" })).toBeVisible();
  });

  test("내 매물 탭은 판매완료 매물까지 보여준다", async ({ page }) => {
    await page.goto("/profile");
    await page.getByRole("button", { name: "내 매물" }).click();

    await expect(page.getByRole("link", { name: /에펠탑 10307/ })).toBeVisible();
    // 검색 API는 활성 매물만 준다. 판매완료가 보인다는 건 전용 조회를 타고 있다는 뜻이다.
    await expect(page.getByText("판매완료")).toBeVisible();
  });

  test("활성 매물은 재확인 후 판매를 중지하고 목록에서 즉시 사라진다", async ({ page }) => {
    let deleteCalls = 0;
    await page.route("**/api/v1/listings/listing-active-1", (route) => {
      deleteCalls += 1;
      return route.fulfill({ status: 204 });
    });
    await page.goto("/profile");
    await page.getByRole("button", { name: "내 매물" }).click();

    await page.getByRole("button", { name: "판매 중지" }).click();
    expect(deleteCalls).toBe(0);
    await expect(
      page.getByText("판매를 중지하면 검색에서 사라지고 되돌릴 수 없어요."),
    ).toBeVisible();

    await page.getByRole("button", { name: "취소" }).click();
    await expect(page.getByText("판매를 중지하면 검색에서 사라지고 되돌릴 수 없어요.")).toHaveCount(
      0,
    );

    await page.getByRole("button", { name: "판매 중지" }).click();
    await page.getByRole("button", { name: "중지하기" }).click();

    await expect.poll(() => deleteCalls).toBe(1);
    await expect(page.getByRole("link", { name: /밀레니엄 팰컨 75192/ })).toHaveCount(0);
    await expect(page.getByRole("status")).toHaveText("매물 판매를 중지했어요.");
  });

  test("판매 중지 실패 시 매물을 유지하고 다시 시도할 수 있다", async ({ page }) => {
    await page.route("**/api/v1/listings/listing-active-1", (route) =>
      route.fulfill({
        status: 409,
        json: { code: "LISTING_ORDER_IN_PROGRESS", message: "예약 중" },
      }),
    );
    await page.goto("/profile");
    await page.getByRole("button", { name: "내 매물" }).click();
    await page.getByRole("button", { name: "판매 중지" }).click();
    await page.getByRole("button", { name: "중지하기" }).click();

    await expect(page.getByText("진행 중인 주문이 있어 판매를 중지할 수 없어요.")).toBeVisible();
    await expect(page.getByRole("link", { name: /밀레니엄 팰컨 75192/ })).toBeVisible();
    await expect(page.getByRole("button", { name: "중지하기" })).toBeEnabled();
  });

  test("구매 내역 탭이 스켈레톤에 머무르지 않는다", async ({ page }) => {
    await page.goto("/profile");
    await page.getByRole("button", { name: "구매 내역" }).click();

    await expect(page.getByRole("link", { name: /ORD-PROF/ })).toBeVisible();
    await expect(page.getByRole("link", { name: /후기 작성/ })).toHaveAttribute(
      "href",
      "/orders/ORD-PROFILE-1#review",
    );
  });

  test("판매 관리 탭이 받은 주문과 정산을 서로 다른 응답으로 채운다", async ({ page }) => {
    await page.goto("/profile");
    await page.getByRole("button", { name: "판매 관리" }).click();

    await expect(page.getByRole("heading", { name: "받은 주문" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "정산" })).toBeVisible();
    await expect(page.getByRole("link", { name: /ORD-SALE ₩310,000/ })).toBeVisible();
    await expect(page.getByRole("link", { name: "ORD-SALE", exact: true })).toBeVisible();
    await expect(page.getByText("₩294,500")).toBeVisible();
    await expect(page.getByText("불러오지 못했어요")).toHaveCount(0);
  });

  test("서버가 세션을 거부하면 stale 로그인 표시를 지우고 재로그인을 안내한다", async ({
    page,
  }) => {
    await page.route("**/api/v1/accounts/me", (route) =>
      route.fulfill({
        status: 401,
        json: { code: "INVALID_SESSION", message: "로그인이 필요합니다" },
      }),
    );

    await page.goto("/profile");

    await expect(page.getByText("로그인이 필요합니다.")).toBeVisible();
    await expect(page.getByRole("link", { name: "로그인하러 가기" })).toHaveAttribute(
      "href",
      "/login?returnTo=%2Fprofile",
    );
    await expect
      .poll(() => page.evaluate(() => window.localStorage.getItem("gole.session")))
      .toBeNull();
  });

  test("조회에 실패하면 로딩이 아니라 실패라고 말하고 다시 시도를 준다", async ({ page }) => {
    // beforeEach가 심어 둔 정상 응답을 실패로 덮어쓴다(나중에 등록한 라우트가 이긴다).
    await page.route("**/api/v1/accounts/me", (route) =>
      route.fulfill({ status: 500, json: { code: "BOOM", message: "실패" } }),
    );

    await page.goto("/profile");

    await expect(page.getByText("불러오지 못했어요")).toBeVisible();
    await expect(page.getByText("불러오는 중")).toHaveCount(0);
    await expect(page.getByRole("button", { name: "다시 시도" })).toBeVisible();
  });

  test("비밀번호 변경은 모든 세션 종료를 안내하고 완료 후 재로그인으로 보낸다", async ({
    page,
  }) => {
    let changeBody: unknown;
    await page.route("**/api/v1/accounts/password", async (route) => {
      changeBody = route.request().postDataJSON();
      await route.fulfill({ status: 204 });
    });

    await page.goto("/profile");
    await page.getByRole("link", { name: "비밀번호 변경" }).click();
    await expect(page.getByRole("heading", { name: "계정 보안" })).toBeVisible();
    await expect(page.getByText("모든 기기에서 로그아웃됩니다")).toBeVisible();

    await page.getByLabel("현재 비밀번호").fill("old-password");
    await page.getByLabel("새 비밀번호", { exact: true }).fill("new-password");
    await page.getByLabel("새 비밀번호 확인").fill("new-password");
    await page.getByRole("button", { name: "비밀번호 변경" }).click();

    expect(changeBody).toEqual({ currentPassword: "old-password", newPassword: "new-password" });
    await expect(page).toHaveURL(/\/login\?passwordChanged=1&returnTo=%2Fprofile/);
    await expect
      .poll(() => page.evaluate(() => window.localStorage.getItem("gole.session")))
      .toBeNull();
  });

  test("메일 발송이 준비 전이면 회원 탈퇴 본인확인 폼 대신 문의 안내를 보여준다", async ({
    page,
  }) => {
    await page.route("**/api/v1/config/launch", (route) =>
      route.fulfill({
        json: {
          stage: 2,
          tradeMode: "MANUAL_SETTLEMENT",
          features: { payments: true, reviews: true, partnerPayout: false },
          sellerIdentityVerificationReady: true,
          emailAuthenticationAvailable: false,
          updatedAt: "2026-09-03T00:00:00Z",
        },
      }),
    );

    await page.goto("/profile/security");

    await expect(page.getByText("탈퇴 본인확인 이메일 발송을 준비하고 있어요.")).toBeVisible();
    await expect(page.getByRole("link", { name: "운영팀에 탈퇴 문의하기" })).toHaveAttribute(
      "href",
      "mailto:coldingcontact@gmail.com?subject=GoLe%20회원%20탈퇴%20문의",
    );
    await expect(page.getByRole("button", { name: "탈퇴 본인확인 코드 받기" })).toHaveCount(0);
  });

  test("회원 탈퇴 접수 직후 계정 관련 브라우저 값만 정리한다", async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem("gole.buyer-phone", "01012345678");
      window.localStorage.setItem("gole.buyer-name", "테스트 구매자");
      window.localStorage.setItem("gole.unrelated-preference", "keep");
      window.sessionStorage.setItem("gole.order.payment-attempted:order-1", "1");
      window.sessionStorage.setItem("gole.order.payment-attempted:order-2", "1");
      window.sessionStorage.setItem("gole.unrelated-tab-state", "keep");
    });
    await page.route("**/api/v1/accounts/me/third-party-provision-consents/current", (route) =>
      route.fulfill({
        json: { noticeVersion: "2026-09-04", consented: false, lastDecisionAt: null },
      }),
    );
    await page.route("**/api/v1/accounts/me/deletion-verifications", (route) =>
      route.fulfill({ status: 204 }),
    );
    await page.route("**/api/v1/accounts/me/deletion-requests", (route) =>
      route.fulfill({
        status: 202,
        json: {
          requestId: "account-deletion-request-1",
          status: "READY",
          blockers: [],
          requestedAt: "2026-09-04T00:00:00Z",
        },
      }),
    );
    page.on("dialog", (dialog) => void dialog.accept());

    await page.goto("/profile/security");
    await page.getByRole("button", { name: "탈퇴 본인확인 코드 받기" }).click();
    await page.getByLabel("현재 계정 이메일").fill("seller@gole.test");
    await page.getByLabel("확인 문구").fill("회원 탈퇴");
    await page.getByLabel("이메일 본인확인 코드").fill("123456");
    await page.getByRole("button", { name: "회원 탈퇴 요청" }).click();

    await expect(page).toHaveURL(/\/login\?deletionRequested=1/);
    await expect
      .poll(() =>
        page.evaluate(() => ({
          session: window.localStorage.getItem("gole.session"),
          buyerPhone: window.localStorage.getItem("gole.buyer-phone"),
          buyerName: window.localStorage.getItem("gole.buyer-name"),
          paymentAttempt1: window.sessionStorage.getItem("gole.order.payment-attempted:order-1"),
          paymentAttempt2: window.sessionStorage.getItem("gole.order.payment-attempted:order-2"),
          unrelatedLocal: window.localStorage.getItem("gole.unrelated-preference"),
          unrelatedSession: window.sessionStorage.getItem("gole.unrelated-tab-state"),
        })),
      )
      .toEqual({
        session: null,
        buyerPhone: null,
        buyerName: null,
        paymentAttempt1: null,
        paymentAttempt2: null,
        unrelatedLocal: "keep",
        unrelatedSession: "keep",
      });
  });
});
