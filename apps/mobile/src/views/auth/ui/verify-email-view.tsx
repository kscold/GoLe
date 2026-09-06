import { useLocalSearchParams, useRouter } from "expo-router";
import { ScrollView, StyleSheet } from "react-native";
import { EmailAuthGate } from "@/entities/launch";
import { VerifyEmailForm } from "@/features/verify-email";
import { space } from "@/shared/theme";
import { Button, EmptyState, Screen, Text } from "@/shared/ui";

/** 이메일 인증 화면. 가입 직후와 미인증 로그인 시도 양쪽에서 들어온다. */
export function VerifyEmailView() {
  const router = useRouter();
  const params = useLocalSearchParams<{ email?: string }>();
  const email = typeof params.email === "string" ? params.email : "";

  if (email.length === 0) {
    // 이메일 없이 들어오면 인증할 대상이 없다. 조용히 빈 폼을 띄우지 않는다.
    return (
      <Screen>
        <EmptyState message="인증할 계정을 알 수 없습니다. 로그인부터 다시 시도해 주세요." />
        <Button label="로그인으로 돌아가기" onPress={() => router.replace("/sign-in")} />
      </Screen>
    );
  }

  return (
    <Screen>
      <ScrollView
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
      >
        <Text variant="title">이메일 인증</Text>
        <EmailAuthGate
          onSignIn={() => router.replace("/sign-in")}
          onBrowse={() => router.replace("/")}
        >
          <VerifyEmailForm email={email} onVerified={() => router.replace("/sign-in")} />
        </EmailAuthGate>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[6], paddingBottom: space[10] },
});
