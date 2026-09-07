const STORAGE_KEY = "gole.pending-verification-email";
const ORIGIN_KEY = "gole.pending-verification-origin";

/** 인증 화면에 어느 동선으로 들어왔는지. 도착 시 코드를 자동 발송할지 판단하는 근거다. */
export type PendingVerificationOrigin = "sign-in" | "sign-up";

/** 인증 대기 이메일을 URL query 대신 현재 탭의 저장소에만 보관한다. */
export function storePendingVerificationEmail(
  email: string,
  origin: PendingVerificationOrigin = "sign-up",
): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.setItem(STORAGE_KEY, email.trim().slice(0, 254));
    window.sessionStorage.setItem(ORIGIN_KEY, origin);
  } catch {
    // 저장소가 차단돼도 인증 화면 자체는 열 수 있다.
  }
}

export function readPendingVerificationEmail(): string {
  if (typeof window === "undefined") return "";
  try {
    return window.sessionStorage.getItem(STORAGE_KEY)?.slice(0, 254) ?? "";
  } catch {
    return "";
  }
}

/**
 * 출처를 읽으면서 지운다. 자동 발송을 이 1회용 소비에 묶어 두면 새로고침·재마운트로
 * 같은 요청이 다시 나가지 않는다.
 */
export function takePendingVerificationOrigin(): PendingVerificationOrigin | null {
  if (typeof window === "undefined") return null;
  try {
    const stored = window.sessionStorage.getItem(ORIGIN_KEY);
    window.sessionStorage.removeItem(ORIGIN_KEY);
    return stored === "sign-in" || stored === "sign-up" ? stored : null;
  } catch {
    return null;
  }
}

export function clearPendingVerificationEmail(): void {
  if (typeof window === "undefined") return;
  try {
    window.sessionStorage.removeItem(STORAGE_KEY);
    window.sessionStorage.removeItem(ORIGIN_KEY);
  } catch {
    // 브라우저 저장소 정리는 best-effort다.
  }
}
