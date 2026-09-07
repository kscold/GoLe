"use client";

import Link from "next/link";
import { type FormEvent, useEffect, useRef, useState } from "react";
import { resendVerificationEmail, verifyEmail } from "@entities/user";
import { ApiError } from "@shared/api";
import { Button, Field, Input } from "@shared/ui";

export interface VerifyEmailFormProps {
  readonly initialEmail?: string;
  /** 로그인에서 넘어온 경우처럼 아직 받은 코드가 없을 때 도착 즉시 한 번 발송한다. */
  readonly autoRequestCode?: boolean;
  /** 방금 코드가 나간 동선(가입 직후 등)만 쿨다운을 걸고 시작한다. */
  readonly initialResendCooldownSeconds?: number;
  readonly loginHref: string;
  readonly resetHref: string;
  readonly onVerified: () => void;
}

export function VerifyEmailForm({
  initialEmail = "",
  autoRequestCode = false,
  initialResendCooldownSeconds = 0,
  loginHref,
  resetHref,
  onVerified,
}: VerifyEmailFormProps) {
  const [email, setEmail] = useState(initialEmail);
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | undefined>(undefined);
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);
  const [resendAfter, setResendAfter] = useState(initialResendCooldownSeconds);
  const [notice, setNotice] = useState<string | undefined>(undefined);
  const autoRequested = useRef(false);

  useEffect(() => {
    if (resendAfter <= 0) return;
    const timer = window.setTimeout(
      () => setResendAfter((seconds) => Math.max(0, seconds - 1)),
      1000,
    );
    return () => window.clearTimeout(timer);
  }, [resendAfter]);

  useEffect(() => {
    if (!autoRequestCode || autoRequested.current || initialEmail.trim() === "") return;
    autoRequested.current = true;
    void handleResend();
    // 도착 시 한 번만 보낸다. 서버의 60초 수신자 쿨다운이 중복 발송을 조용히 흡수한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoRequestCode, initialEmail]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError(undefined);
    setSubmitting(true);
    try {
      await verifyEmail(email, code);
      onVerified();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "인증 중 오류가 발생했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleResend() {
    setError(undefined);
    setNotice(undefined);
    setResending(true);
    try {
      await resendVerificationEmail(email);
      // 서버는 계정 존재 여부를 노출하지 않으려고 항상 204를 준다. 문구도 그 계약에 맞춘다.
      setNotice("인증이 필요한 계정이면 코드를 보내드립니다. 메일함을 확인해 주세요.");
      setResendAfter(60);
    } catch (cause) {
      setError(
        cause instanceof ApiError ? cause.message : "인증 코드 재발급 중 오류가 발생했습니다.",
      );
    } finally {
      setResending(false);
    }
  }

  return (
    <form className="flex flex-col gap-4" onSubmit={handleSubmit} noValidate>
      {error ? (
        <p className="p-3 rounded-md bg-danger-soft text-danger text-sm" role="alert">
          {error}
        </p>
      ) : null}
      {notice ? (
        <p
          className="p-3 rounded-md bg-success-soft text-success text-sm"
          role="status"
          aria-live="polite"
        >
          {notice}
        </p>
      ) : null}
      <Field label="이메일">
        {({ inputId, describedBy }) => (
          <Input
            id={inputId}
            type="email"
            autoComplete="email"
            value={email}
            aria-describedby={describedBy}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
        )}
      </Field>
      <Field label="인증 코드" hint="이메일로 받은 6자리 코드를 입력하세요.">
        {({ inputId, describedBy }) => (
          <Input
            id={inputId}
            inputMode="numeric"
            autoComplete="one-time-code"
            value={code}
            placeholder="000000"
            aria-describedby={describedBy}
            onChange={(e) => setCode(e.target.value)}
            required
          />
        )}
      </Field>
      <Button type="submit" size="lg" fullWidth disabled={submitting}>
        {submitting ? "확인 중..." : "인증하기"}
      </Button>
      <Button
        type="button"
        variant="ghost"
        fullWidth
        disabled={resending || resendAfter > 0 || email.trim() === ""}
        onClick={handleResend}
      >
        {resending
          ? "다시 보내는 중..."
          : resendAfter > 0
            ? `${resendAfter}초 후 인증 코드 다시 받기`
            : "인증 코드 다시 받기"}
      </Button>
      {/*
        인증을 이미 마친 이메일로 다시 가입해도 서버는 계정 존재를 감추려고 같은 성공 응답을
        준다. 그 사용자에게는 코드가 영원히 오지 않으므로 출구가 화면에 있어야 한다.
        아래 문구는 신규·기존 계정 모두에게 참이라 아무것도 누설하지 않는다.
      */}
      <div className="rounded-xl bg-neutral-50 p-4 text-sm leading-relaxed text-neutral-600">
        <p>코드가 오지 않나요? 이미 가입을 마친 계정일 수 있어요.</p>
        <div className="mt-2 flex flex-wrap gap-4">
          <Link
            className="font-semibold text-brand-700 underline underline-offset-4"
            href={loginHref}
          >
            로그인하기
          </Link>
          <Link
            className="font-semibold text-brand-700 underline underline-offset-4"
            href={resetHref}
          >
            비밀번호 찾기
          </Link>
        </div>
      </div>
    </form>
  );
}
