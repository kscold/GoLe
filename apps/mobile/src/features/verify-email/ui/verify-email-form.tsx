import { useEffect, useRef, useState } from "react";
import { StyleSheet, View } from "react-native";
import { ApiError } from "@gole/core";
import { resendVerificationEmail, verifyEmail } from "@gole/core/user";
import { space } from "@/shared/theme";
import { Button, Text, TextField } from "@/shared/ui";

export interface VerifyEmailFormProps {
  readonly email: string;
  /** 로그인에서 넘어온 경우처럼 아직 받은 코드가 없을 때 도착 즉시 한 번 발송한다. */
  readonly autoRequestCode?: boolean;
  /** 방금 코드가 나간 동선(가입 직후 등)만 쿨다운을 걸고 시작한다. */
  readonly initialResendCooldownSeconds?: number;
  readonly onSignIn: () => void;
  readonly onVerified: () => void;
}

export function VerifyEmailForm({
  email,
  autoRequestCode = false,
  initialResendCooldownSeconds = 0,
  onSignIn,
  onVerified,
}: VerifyEmailFormProps) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);
  const [resendAfter, setResendAfter] = useState(initialResendCooldownSeconds);
  const autoRequested = useRef(false);

  // 서버는 60초 안의 재요청을 조용히 무시한다. 아무 효과 없는 버튼을 반복해서 누르지 않도록
  // 웹과 같은 카운트다운을 보여준다.
  useEffect(() => {
    if (resendAfter <= 0) return;
    const timer = setTimeout(() => setResendAfter((seconds) => Math.max(0, seconds - 1)), 1000);
    return () => clearTimeout(timer);
  }, [resendAfter]);

  useEffect(() => {
    if (!autoRequestCode || autoRequested.current || email.trim().length === 0) return;
    autoRequested.current = true;
    void handleResend();
    // 도착 시 한 번만 보낸다. 서버의 60초 수신자 쿨다운이 중복 발송을 조용히 흡수한다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoRequestCode, email]);

  async function handleVerify(): Promise<void> {
    if (code.trim().length === 0 || submitting || resending) return;
    setError(null);
    setNotice(null);
    setSubmitting(true);
    try {
      await verifyEmail(email, code.trim());
      onVerified();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "인증하지 못했습니다.");
    } finally {
      setSubmitting(false);
    }
  }

  async function handleResend(): Promise<void> {
    if (submitting || resending) return;
    setError(null);
    setNotice(null);
    setResending(true);
    try {
      await resendVerificationEmail(email);
      // 서버는 계정 존재 여부를 노출하지 않으려고 항상 204를 준다. 문구도 그 계약에 맞춘다.
      setNotice("인증이 필요한 계정이면 코드를 보내드립니다. 메일함을 확인해 주세요.");
      setResendAfter(60);
    } catch {
      setError("인증 코드를 다시 보내지 못했습니다.");
    } finally {
      setResending(false);
    }
  }

  return (
    <View style={styles.form}>
      <Text muted>{email}으로 보낸 인증 코드를 입력해 주세요.</Text>
      {error === null ? null : (
        <Text variant="caption" style={styles.error} accessibilityRole="alert">
          {error}
        </Text>
      )}
      {notice === null ? null : (
        <Text variant="caption" muted>
          {notice}
        </Text>
      )}
      <TextField
        label="인증 코드"
        value={code}
        onChangeText={setCode}
        autoCapitalize="none"
        keyboardType="number-pad"
        textContentType="oneTimeCode"
        onSubmitEditing={() => void handleVerify()}
      />
      <Button
        label="인증하기"
        onPress={() => void handleVerify()}
        loading={submitting}
        disabled={code.trim().length === 0 || resending}
      />
      <Button
        label={resendAfter > 0 ? `${resendAfter}초 후 코드 다시 받기` : "코드 다시 받기"}
        variant="secondary"
        onPress={() => void handleResend()}
        loading={resending}
        disabled={submitting || resendAfter > 0}
      />
      {/*
        인증을 이미 마친 이메일로 다시 가입해도 서버는 계정 존재를 감추려고 같은 성공 응답을
        준다. 그 사용자에게는 코드가 영원히 오지 않으므로 출구가 화면에 있어야 한다.
        아래 문구는 신규·기존 계정 모두에게 참이라 아무것도 누설하지 않는다.
      */}
      <Text variant="caption" muted>
        코드가 오지 않나요? 이미 가입을 마친 계정일 수 있어요.
      </Text>
      <Button label="기존 계정으로 로그인" variant="secondary" onPress={onSignIn} />
    </View>
  );
}

const styles = StyleSheet.create({
  form: { gap: space[4] },
  error: { color: "#dc2626" },
});
