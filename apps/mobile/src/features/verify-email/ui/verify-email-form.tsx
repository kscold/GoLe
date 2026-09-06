import { useState } from "react";
import { StyleSheet, View } from "react-native";
import { ApiError } from "@gole/core";
import { resendVerificationEmail, verifyEmail } from "@gole/core/user";
import { space } from "@/shared/theme";
import { Button, Text, TextField } from "@/shared/ui";

export interface VerifyEmailFormProps {
  readonly email: string;
  readonly onVerified: () => void;
}

export function VerifyEmailForm({ email, onVerified }: VerifyEmailFormProps) {
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [resending, setResending] = useState(false);

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
        label="코드 다시 받기"
        variant="secondary"
        onPress={() => void handleResend()}
        loading={resending}
        disabled={submitting}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  form: { gap: space[4] },
  error: { color: "#dc2626" },
});
