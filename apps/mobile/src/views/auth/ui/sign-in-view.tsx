import { useRouter } from "expo-router";
import { ScrollView, StyleSheet } from "react-native";
import { SignInForm } from "@/features/sign-in";
import { space } from "@/shared/theme";
import { Button, Screen, Text } from "@/shared/ui";

/** 로그인 화면. 웹 `views/auth`의 로그인 단계에 대응한다. */
export function SignInView() {
  const router = useRouter();

  return (
    <Screen>
      <ScrollView
        contentContainerStyle={styles.content}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="on-drag"
      >
        <Text variant="title">로그인</Text>
        <SignInForm
          onSignedIn={() => router.replace("/")}
          onNeedsVerification={(email) =>
            router.replace({ pathname: "/verify-email", params: { email, origin: "sign-in" } })
          }
        />
        <Button
          label="계정이 없으신가요? 가입하기"
          variant="secondary"
          onPress={() => router.push("/sign-up")}
        />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[6], paddingBottom: space[10] },
});
