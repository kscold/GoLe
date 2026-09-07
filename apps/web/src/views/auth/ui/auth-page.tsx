"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useRef, useState } from "react";
import { SignInForm } from "@features/sign-in";
import { SignUpForm } from "@features/sign-up";
import { SocialLoginButtons } from "@features/social-login";
import { fetchLaunchConfig } from "@entities/launch";
import {
  fetchCurrentSignupPolicy,
  type CurrentSignupPolicy,
  type SignupPolicyAcceptance,
  useSession,
} from "@entities/user";
import { AuthCard } from "@widgets/auth-layout";
import { Button } from "@shared/ui";
import { clearPendingVerificationEmail, storePendingVerificationEmail } from "@shared/lib";
import { applyRoleGuard, resolveReturnTo, returnToLabel } from "../model/return-to";

export interface AuthPageProps {
  /** 소셜 첫 가입 직후 온보딩 환영 화면 표시 여부(서버에서 ?welcome=1 판별). */
  readonly welcome?: boolean;
}

/**
 * 통합 인증 화면. 로그인/회원가입을 탭으로 전환하며(URL `/login`·`/signup` 구동),
 * 로컬(이메일) + 소셜(Google/Kakao/Naver) 4가지 진입을 한 화면에서 제공한다.
 *
 * `?returnTo=`로 들어온 복귀 경로는 검증 후에만 사용하고, 인증이 끝나면 그 화면으로 돌려보낸다.
 * `useSearchParams`를 쓰므로 Suspense 경계를 슬라이스 안에서 직접 제공한다.
 */
export function AuthPage({ welcome = false }: AuthPageProps) {
  return (
    <Suspense fallback={<AuthFallback welcome={welcome} />}>
      <AuthPageContent welcome={welcome} />
    </Suspense>
  );
}

/** Suspense 대기 중 레이아웃이 튀지 않도록 같은 카드 골격을 유지한다. */
function AuthFallback({ welcome }: { readonly welcome: boolean }) {
  return (
    <AuthCard title={welcome ? "환영합니다" : "로그인"}>
      <p role="status" aria-live="polite" className="py-6 text-center text-sm text-neutral-500">
        불러오는 중…
      </p>
    </AuthCard>
  );
}

function AuthPageContent({ welcome }: { readonly welcome: boolean }) {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const { session } = useSession();
  const mode = pathname === "/signup" ? "signup" : "signin";

  // 형태 검증만 통과한 값. 실제 이동 직전에 역할 게이트를 한 번 더 적용한다.
  const returnTo = resolveReturnTo(searchParams.get("returnTo"));
  const passwordChanged =
    searchParams.get("passwordChanged") === "1" || searchParams.get("passwordReset") === "1";
  const [signupPolicy, setSignupPolicy] = useState<CurrentSignupPolicy | undefined>(undefined);
  const [emailAuthenticationAvailable, setEmailAuthenticationAvailable] = useState<boolean | null>(
    null,
  );
  const [signupPolicyError, setSignupPolicyError] = useState<string | undefined>(undefined);
  const [signupPolicyAcceptance, setSignupPolicyAcceptance] = useState<SignupPolicyAcceptance>({
    termsVersion: "",
    privacyVersion: "",
    thirdPartyProvisionVersion: "",
    termsAccepted: false,
    privacyAcknowledged: false,
    thirdPartyProvisionAccepted: false,
    minimumAgeConfirmed: false,
  });

  useEffect(() => {
    const controller = new AbortController();
    void fetchLaunchConfig(controller.signal).then((config) => {
      if (!controller.signal.aborted) {
        setEmailAuthenticationAvailable(config.emailAuthenticationAvailable);
      }
    });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    if (mode !== "signup" || signupPolicy !== undefined) {
      return;
    }
    const controller = new AbortController();
    fetchCurrentSignupPolicy(controller.signal)
      .then((policy) => {
        setSignupPolicy(policy);
        setSignupPolicyAcceptance((current) => ({
          ...current,
          termsVersion: policy.termsVersion,
          privacyVersion: policy.privacyVersion,
          thirdPartyProvisionVersion: policy.thirdPartyProvisionVersion,
        }));
      })
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === "AbortError") {
          return;
        }
        setSignupPolicyError("최신 정책을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
      });
    return () => controller.abort();
  }, [mode, signupPolicy]);

  /**
   * 인증 성공 후 이동. 히스토리에 /login을 남기지 않도록 replace를 쓴다.
   *
   * 온보딩이 남은 계정은 목적지 대신 온보딩으로 보내고, 원래 가려던 곳을 `returnTo`로
   * 넘겨 끝난 뒤 이어서 도착하게 한다(R12).
   */
  function goAfterAuth(
    role: "USER" | "ADMIN" | null | undefined,
    onboardingRequired = false,
  ): void {
    clearPendingVerificationEmail();
    const target = applyRoleGuard(returnTo, role) ?? "/";
    if (onboardingRequired) {
      router.replace(`/onboarding?${new URLSearchParams({ returnTo: target }).toString()}`);
      return;
    }
    router.replace(target);
  }

  if (welcome) {
    return (
      <AuthCard title="환영합니다" subtitle="소셜 계정으로 가입이 완료됐어요.">
        <div className="flex flex-col gap-5">
          <p className="text-sm leading-relaxed text-neutral-600">
            이제 GoLe에서 브릭 시세를 확인하고, 판매자와 대화하고, 컬렉션을 자랑할 수 있어요.
          </p>
          <Button
            size="lg"
            fullWidth
            disabled={session === null}
            onClick={() => goAfterAuth(session?.role ?? null, session?.onboardingRequired ?? false)}
          >
            {session === null ? "세션 확인 중…" : "시작하기"}
          </Button>
        </div>
      </AuthCard>
    );
  }

  function tabClass(active: boolean): string {
    return `block border-b-2 py-2.5 text-center text-sm font-semibold transition-colors ${
      active
        ? "border-brand-600 text-brand-700"
        : "border-transparent text-neutral-500 hover:border-neutral-300 hover:text-neutral-800"
    }`;
  }

  /** 탭 전환 시에도 복귀 경로를 잃지 않는다. */
  function tabHref(target: "/login" | "/signup"): string {
    return returnTo === null ? target : `${target}?returnTo=${encodeURIComponent(returnTo)}`;
  }

  /** 가입 직후와 미인증 로그인이 같은 인증 화면으로 모이고, 둘 다 복귀 경로를 유지한다. */
  function verifyHref(): string {
    return returnTo === null ? "/verify" : `/verify?returnTo=${encodeURIComponent(returnTo)}`;
  }

  return (
    <AuthCard
      title={mode === "signup" ? "회원가입" : "로그인"}
      subtitle={
        mode === "signup" ? "브릭을 사고팔고 컬렉션을 자랑해보세요." : "다시 오신 것을 환영합니다."
      }
    >
      <div className="flex flex-col gap-5">
        {returnTo === null ? null : <ReturnToNotice target={returnTo} />}
        {passwordChanged ? (
          <p role="status" className="rounded-md bg-success-soft p-3 text-sm text-success">
            비밀번호가 변경됐어요. 새 비밀번호로 로그인해 주세요.
          </p>
        ) : null}

        <div
          role="tablist"
          aria-label="인증 방식"
          className="grid grid-cols-2 border-b border-neutral-200"
        >
          <Link
            href={tabHref("/login")}
            role="tab"
            aria-selected={mode === "signin"}
            className={tabClass(mode === "signin")}
          >
            로그인
          </Link>
          <Link
            href={tabHref("/signup")}
            role="tab"
            aria-selected={mode === "signup"}
            className={tabClass(mode === "signup")}
          >
            회원가입
          </Link>
        </div>

        {mode === "signin" ? (
          <SignInForm
            emailAuthenticationAvailable={emailAuthenticationAvailable === true}
            resetHref={
              returnTo === null
                ? "/forgot-password"
                : `/forgot-password?returnTo=${encodeURIComponent(returnTo)}`
            }
            onSignedIn={(signedIn) => goAfterAuth(signedIn.role, signedIn.onboardingRequired)}
            onNeedsVerification={(email) => {
              storePendingVerificationEmail(email, "sign-in");
              router.push(verifyHref());
            }}
          />
        ) : (
          <SignUpForm
            emailRegistrationAvailable={emailAuthenticationAvailable}
            policy={signupPolicy}
            policyError={signupPolicyError}
            policyAcceptance={signupPolicyAcceptance}
            onPolicyAcceptanceChange={setSignupPolicyAcceptance}
            onRegistered={(email) => {
              storePendingVerificationEmail(email, "sign-up");
              router.push(verifyHref());
            }}
          />
        )}

        <SocialLoginButtons
          mode={mode}
          signupPolicyAcceptance={mode === "signup" ? signupPolicyAcceptance : undefined}
          returnTo={returnTo}
        />
      </div>
    </AuthCard>
  );
}

/**
 * 왜 로그인 화면에 왔는지와 어디로 돌아가는지를 알린다.
 * 화면 진입 시 포커스를 받아 스크린 리더가 문맥을 먼저 읽도록 한다.
 */
function ReturnToNotice({ target }: { readonly target: string }) {
  const ref = useRef<HTMLParagraphElement | null>(null);

  useEffect(() => {
    ref.current?.focus();
  }, []);

  return (
    <p
      ref={ref}
      tabIndex={-1}
      role="status"
      data-testid="return-to-notice"
      data-return-to={target}
      className="rounded-md bg-brand-50 p-3 text-sm text-brand-800"
    >
      로그인하면 <strong className="font-semibold">{returnToLabel(target)}</strong> 화면으로
      돌아갑니다.
    </p>
  );
}
