import type { ReactNode } from "react";
import { StyleSheet, View } from "react-native";
import { fetchLaunchConfig } from "@gole/core/launch";
import { useAsync } from "@/shared/lib";
import { space } from "@/shared/theme";
import { Button, LoadingState, Text } from "@/shared/ui";

interface EmailAuthGateProps {
  readonly children: ReactNode;
  readonly onSignIn: () => void;
  readonly onBrowse: () => void;
}

/** 서버가 명시적으로 허용한 경우에만 이메일 입력·인증 폼을 마운트한다. */
export function EmailAuthGate({ children, onSignIn, onBrowse }: EmailAuthGateProps) {
  const launch = useAsync((signal) => fetchLaunchConfig(signal), []);

  if (launch.loading) {
    return <LoadingState label="이메일 인증 이용 가능 여부를 확인하는 중" />;
  }
  if (launch.error !== null || launch.data?.emailAuthenticationAvailable !== true) {
    return (
      <View style={styles.content}>
        <Text accessibilityRole="alert">이메일 가입·인증은 현재 이용할 수 없습니다.</Text>
        <Text muted>
          메일 발송이 준비되면 이용할 수 있습니다. 이미 인증된 계정으로 로그인하거나 브릭과
          커뮤니티를 먼저 둘러보세요.
        </Text>
        <Button label="다시 확인" onPress={launch.reload} variant="secondary" />
        <Button label="기존 계정으로 로그인" onPress={onSignIn} />
        <Button label="둘러보기" onPress={onBrowse} variant="secondary" />
      </View>
    );
  }
  return children;
}

const styles = StyleSheet.create({ content: { gap: space[4] } });
