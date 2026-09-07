"use client";

import Link from "next/link";
import { Suspense, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { fetchLaunchConfig } from "@entities/launch";
import { VerifyEmailForm } from "@features/verify-email";
import { AuthCard } from "@widgets/auth-layout";
import {
  clearPendingVerificationEmail,
  readPendingVerificationEmail,
  resolveReturnTo,
  takePendingVerificationOrigin,
} from "@shared/lib";

function VerifyEmailContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const hasLegacyEmailQuery = searchParams.has("email");
  const [email] = useState(readPendingVerificationEmail);
  // 읽으면서 지우는 1회용 마커다. 새로고침해도 코드가 다시 나가지 않는다.
  const [origin] = useState(takePendingVerificationOrigin);
  const returnTo = resolveReturnTo(searchParams.get("returnTo"));
  const loginHref =
    returnTo === null ? "/login" : `/login?returnTo=${encodeURIComponent(returnTo)}`;
  const resetHref =
    returnTo === null
      ? "/forgot-password"
      : `/forgot-password?returnTo=${encodeURIComponent(returnTo)}`;
  const [emailAuthenticationAvailable, setEmailAuthenticationAvailable] = useState<boolean | null>(
    null,
  );

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
    if (hasLegacyEmailQuery) {
      const next = new URLSearchParams();
      if (returnTo !== null) next.set("returnTo", returnTo);
      const query = next.toString();
      router.replace(query.length === 0 ? "/verify" : `/verify?${query}`);
    }
  }, [hasLegacyEmailQuery, returnTo, router]);

  if (emailAuthenticationAvailable === null) {
    return (
      <p role="status" className="py-6 text-center text-sm text-neutral-500">
        이메일 발송 상태를 확인하는 중…
      </p>
    );
  }

  if (!emailAuthenticationAvailable) {
    return (
      <div className="flex flex-col gap-4">
        <p
          role="status"
          className="rounded-xl bg-brand-50 p-4 text-sm leading-relaxed text-brand-900"
        >
          이메일 인증 코드 발송을 준비하고 있어요. 준비 전에는 이메일 가입을 완료할 수 없습니다.
        </p>
        <Link className="text-center text-sm font-semibold text-brand-700" href={loginHref}>
          로그인으로 돌아가기
        </Link>
        <a
          className="text-center text-sm font-semibold text-neutral-600 underline underline-offset-4"
          href="mailto:coldingcontact@gmail.com?subject=GoLe%20이메일%20인증%20문의"
        >
          운영팀에 이메일 보내기
        </a>
      </div>
    );
  }

  return (
    <VerifyEmailForm
      initialEmail={email}
      // 로그인 경유는 아직 코드가 없다. 가입 직후는 register가 이미 보냈고 쿨다운도 소비했다.
      autoRequestCode={origin === "sign-in"}
      initialResendCooldownSeconds={origin === null ? 0 : 60}
      loginHref={loginHref}
      resetHref={resetHref}
      onVerified={() => {
        clearPendingVerificationEmail();
        router.replace(loginHref);
      }}
    />
  );
}

export function VerifyEmailPage() {
  return (
    <AuthCard title="이메일 인증" subtitle="받은 인증 코드를 입력해 가입을 완료하세요.">
      <Suspense fallback={null}>
        <VerifyEmailContent />
      </Suspense>
    </AuthCard>
  );
}
