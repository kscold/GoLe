"use client";

import Link from "next/link";
import { type FormEvent, useState } from "react";
import { saveSession, signIn, type Session } from "@entities/user";
import { ApiError } from "@shared/api";
import { Button, Field, Input } from "@shared/ui";

export interface SignInFormProps {
  readonly onSignedIn: (session: Session) => void;
  /** 인증이 안 끝난 계정이면 인증 화면으로 보낸다. 이메일을 넘겨 다시 입력하지 않게 한다. */
  readonly onNeedsVerification: (email: string) => void;
  readonly resetHref?: string;
  readonly emailAuthenticationAvailable?: boolean;
}

export function SignInForm({
  onSignedIn,
  onNeedsVerification,
  resetHref = "/forgot-password",
  emailAuthenticationAvailable = true,
}: SignInFormProps) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | undefined>(undefined);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    setSubmitting(true);
    try {
      const session = await signIn(email, password);
      saveSession(session);
      onSignedIn(session);
    } catch (cause) {
      // 이메일 인증 전 로그인은 실패가 아니라 다음 단계다. 오류로 보여주면 사용자가 막힌다.
      // 다만 메일 발송이 준비되기 전에는 보낼 곳이 없으므로 그때만 오류로 남긴다.
      if (
        cause instanceof ApiError &&
        cause.code === "ACCOUNT_NOT_VERIFIED" &&
        emailAuthenticationAvailable
      ) {
        onNeedsVerification(email.trim());
        return;
      }
      setError(cause instanceof ApiError ? cause.message : "로그인 중 오류가 발생했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
      {error ? (
        <div className="p-3 rounded-md bg-danger-soft text-danger text-sm" role="alert">
          <p>{error}</p>
        </div>
      ) : null}
      <Field label="이메일">
        {({ inputId, describedBy }) => (
          <Input
            id={inputId}
            type="email"
            autoComplete="email"
            value={email}
            placeholder="you@example.com"
            aria-describedby={describedBy}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        )}
      </Field>
      <Field label="비밀번호">
        {({ inputId, describedBy }) => (
          <Input
            id={inputId}
            type="password"
            autoComplete="current-password"
            value={password}
            placeholder="••••••••"
            aria-describedby={describedBy}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        )}
      </Field>
      {emailAuthenticationAvailable ? (
        <div className="-mt-2 flex justify-end">
          <Link
            href={resetHref}
            className="text-sm font-semibold text-brand-700 underline-offset-4 hover:underline"
          >
            비밀번호를 잊으셨나요?
          </Link>
        </div>
      ) : null}
      <Button type="submit" size="lg" fullWidth disabled={submitting}>
        {submitting ? "로그인 중..." : "로그인"}
      </Button>
    </form>
  );
}
