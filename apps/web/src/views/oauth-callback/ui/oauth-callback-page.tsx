"use client";

import { useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { saveSession, socialCallback } from "@entities/user";
import { ApiError } from "@shared/api";
import { isAdminPath, resolveReturnTo } from "@shared/lib";
import { Container, Heading, LinkButton, Text } from "@shared/ui";

export interface OAuthCallbackPageProps {
  readonly provider: string;
}

/**
 * OAuth 콜백 처리. provider에서 돌아온 code/state를 검증하고 세션을 저장한 뒤 state에 결박된
 * 복귀 화면으로 이동한다. (소셜 로그인 스펙 S9)
 */
export function OAuthCallbackPage({ provider }: OAuthCallbackPageProps) {
  const router = useRouter();
  const params = useSearchParams();
  const [error, setError] = useState<string | undefined>(undefined);
  const [errorCode, setErrorCode] = useState<string | undefined>(undefined);
  const ranRef = useRef(false);

  useEffect(() => {
    if (ranRef.current) {
      return; // StrictMode 이중 실행 방지(코드는 1회용)
    }
    ranRef.current = true;

    const run = async (): Promise<void> => {
      const code = params.get("code");
      const returnedState = params.get("state");
      const providerError = params.get("error");

      if (providerError !== null) {
        setError("소셜 로그인이 취소되었거나 거부되었습니다.");
        return;
      }
      if (code === null || returnedState === null) {
        setError("인증 정보가 올바르지 않습니다.");
        return;
      }

      const redirectUri = `${window.location.origin}/auth/callback/${provider}`;
      try {
        // state는 서버가 발급·검증한다(CSRF). 콜백에서 그대로 전달만 한다.
        const { session, newAccount, returnTo } = await socialCallback(
          provider,
          code,
          redirectUri,
          returnedState,
        );
        saveSession(session);

        const requestedTarget = resolveReturnTo(returnTo);
        const target =
          requestedTarget !== null && (!isAdminPath(requestedTarget) || session.role === "ADMIN")
            ? requestedTarget
            : "/";

        if (session.onboardingRequired) {
          router.replace(`/onboarding?${new URLSearchParams({ returnTo: target }).toString()}`);
          return;
        }
        if (newAccount) {
          const next = new URLSearchParams({ welcome: "1", returnTo: target });
          router.replace(`/signup?${next.toString()}`);
          return;
        }
        router.replace(target);
      } catch (cause) {
        // 미가입 Google 계정은 여기서 처음 걸러진다 — signup 동의가 state에 없었기 때문에
        // 서버가 회원가입 자체를 거부한 것이지, 로그인 정보가 틀린 게 아니다. 같은 /login으로
        // 돌려보내면 사용자가 이 화면을 영원히 반복하게 되므로 /signup으로 갈라야 한다.
        if (cause instanceof ApiError && cause.code === "POLICY_ACCEPTANCE_REQUIRED") {
          setErrorCode(cause.code);
          setError(
            "이 Google 계정은 아직 가입되어 있지 않아요. 가입하려면 이용약관 확인, 개인정보처리방침 확인, 만 14세 이상 확인에 먼저 동의해야 해요.",
          );
          return;
        }
        setError(
          cause instanceof ApiError ? cause.message : "소셜 로그인 처리 중 오류가 발생했습니다.",
        );
      }
    };

    void run();
  }, [params, provider, router]);

  return (
    <Container width="sm">
      <div className="flex flex-col items-start gap-4 pt-16 pb-20">
        {error === undefined ? (
          <>
            <Heading level={1}>로그인 처리 중...</Heading>
            <Text tone="secondary">잠시만 기다려 주세요.</Text>
          </>
        ) : errorCode === "POLICY_ACCEPTANCE_REQUIRED" ? (
          <>
            {/* 인증은 성공했고 가입만 안 된 상태다. "실패"라고 하면 원인을 잘못 짚게 한다. */}
            <Heading level={1}>가입이 필요해요</Heading>
            <Text tone="secondary">{error}</Text>
            {/* 복귀 경로는 성공 응답에만 실려 오므로 이 분기에서는 알 수 없다. 가입을 마치면
                가입 화면이 자체 흐름대로 보내 준다. */}
            <LinkButton href="/signup">회원가입 화면으로</LinkButton>
          </>
        ) : (
          <>
            <Heading level={1}>로그인 실패</Heading>
            <Text tone="secondary">{error}</Text>
            <LinkButton href="/login">로그인 화면으로</LinkButton>
          </>
        )}
      </div>
    </Container>
  );
}
