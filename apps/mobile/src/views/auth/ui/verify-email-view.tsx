import { useLocalSearchParams, useRouter } from "expo-router";
import { ScrollView, StyleSheet } from "react-native";
import { EmailAuthGate } from "@/entities/launch";
import { VerifyEmailForm } from "@/features/verify-email";
import { space } from "@/shared/theme";
import { Button, EmptyState, Screen, Text } from "@/shared/ui";

/** 이메일 인증 화면. 가입 직후와 미인증 로그인 시도 양쪽에서 들어온다. */
export function VerifyEmailView() {
  const router = useRouter();
  const params = useLocalSearchParams<{ email?: string; origin?: string }>();
  const email = typeof params.email === "string" ? params.email : "";
  // 웹은 sessionStorage 1회용 마커를 쓰지만 여기서는 이미 이메일을 넘기는 라우트 파라미터가
  // 같은 역할을 한다. 진입이 replace라 뒤로가기로 다시 들어오지 않는다.
  const origin = params.origin === "sign-in" || params.origin === "sign-up" ? params.origin : null;

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
          <VerifyEmailForm
            email={email}
            // 로그인 경유는 아직 코드가 없다. 가입 직후는 register가 이미 보냈다.
            autoRequestCode={origin === "sign-in"}
            initialResendCooldownSeconds={origin === null ? 0 : 60}
            onSignIn={() => router.replace("/sign-in")}
            onVerified={() => router.replace("/sign-in")}
          />
        </EmailAuthGate>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[6], paddingBottom: space[10] },
});
