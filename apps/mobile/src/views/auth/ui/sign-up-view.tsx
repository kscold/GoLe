import { useRouter } from "expo-router";
import { ScrollView, StyleSheet } from "react-native";
import { EmailAuthGate } from "@/entities/launch";
import { SignUpForm } from "@/features/sign-up";
import { space } from "@/shared/theme";
import { Screen, Text } from "@/shared/ui";

/** 회원가입 화면. 가입 후 인증 단계로 이어진다. */
export function SignUpView() {
  const router = useRouter();

  return (
    <Screen>
      <ScrollView
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
      >
        <Text variant="title">가입하기</Text>
        <EmailAuthGate
          onSignIn={() => router.replace("/sign-in")}
          onBrowse={() => router.replace("/")}
        >
          <SignUpForm
            onRegistered={(email) =>
              router.replace({ pathname: "/verify-email", params: { email } })
            }
          />
        </EmailAuthGate>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[6], paddingBottom: space[10] },
});
