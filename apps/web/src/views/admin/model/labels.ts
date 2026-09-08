import type { BadgeTone } from "@shared/ui";

export const ORDER_STATUS_LABEL: Readonly<Record<string, string>> = {
  PAYMENT_PENDING: "결제대기",
  PAYMENT_REVIEW: "결제확인필요",
  FUNDS_HELD: "자금보유",
  REFUND_PENDING: "환불처리중",
  COMPLETED: "완료",
  REFUNDED: "환불",
  PAYMENT_FAILED: "결제실패",
};

export const LISTING_STATUS_LABEL: Readonly<Record<string, string>> = {
  ACTIVE: "판매중",
  RESERVED: "예약중",
  SOLD: "판매완료",
  DELETED: "내려짐",
};

export const LISTING_STATUS_TONE: Readonly<Record<string, BadgeTone>> = {
  ACTIVE: "success",
  RESERVED: "warning",
  SOLD: "neutral",
  DELETED: "danger",
};

export const REPORT_REASON_LABEL: Readonly<Record<string, string>> = {
  COUNTERFEIT: "가품 의심",
  IP_INFRINGEMENT: "이미지 도용",
  FRAUD: "사기·허위",
  INAPPROPRIATE: "부적절",
  OTHER: "기타",
};

export const REPORT_STATUS_LABEL: Readonly<Record<string, string>> = {
  PENDING: "접수",
  RESOLVED: "조치완료",
  DISMISSED: "기각",
};

export const REPORT_STATUS_TONE: Readonly<Record<string, BadgeTone>> = {
  PENDING: "warning",
  RESOLVED: "success",
  DISMISSED: "neutral",
};

export const ACCOUNT_STATUS_LABEL: Readonly<Record<string, string>> = {
  UNVERIFIED: "미인증",
  VERIFIED: "정상",
  SUSPENDED: "정지",
};

export const ACCOUNT_STATUS_TONE: Readonly<Record<string, BadgeTone>> = {
  UNVERIFIED: "neutral",
  VERIFIED: "success",
  SUSPENDED: "danger",
};

export const PROMOTION_POST_STATUS_LABEL: Readonly<Record<string, string>> = {
  DRAFT: "초안",
  PENDING_REVIEW: "검토대기",
  APPROVED: "승인됨",
  PUBLISHED: "발행완료",
};

export const PROMOTION_POST_STATUS_TONE: Readonly<Record<string, BadgeTone>> = {
  DRAFT: "neutral",
  PENDING_REVIEW: "warning",
  APPROVED: "brand",
  PUBLISHED: "success",
};

export const PROMOTION_CHANNEL_LABEL: Readonly<Record<string, string>> = {
  THREADS: "Threads",
};

export const COUNT_LABEL: Readonly<Record<string, string>> = {
  accounts: "회원",
  lego_sets: "카탈로그 세트",
  listings: "매물",
  orders: "주문",
  posts: "게시글",
  reviews: "후기",
  price_transactions: "시세 원장 전체",
};

/** 감사 로그의 조치 유형을 운영자가 읽는 말로 옮긴다. */
export const AUDIT_TYPE_LABEL: Readonly<Record<string, string>> = {
  LISTING_TAKEDOWN: "매물 내림",
  POST_REMOVE: "게시글 삭제",
  COMMENT_HIDE: "댓글 블라인드",
  ACCOUNT_SUSPEND: "회원 정지",
  ACCOUNT_REINSTATE: "정지 해제",
  ACCOUNT_ROLE_CHANGE: "권한 변경",
  REPORT_RESOLVE: "신고 조치완료",
  REPORT_DISMISS: "신고 기각",
  REVIEW_HIDE: "후기 블라인드",
  COMMENT_REPORT_CONTEXT_VIEW: "댓글 신고 문맥 열람",
  CATALOG_SET_CREATE: "세트 등록",
  CATALOG_SET_UPDATE: "세트 수정",
  CATALOG_SET_FEATURE: "추천 토글",
  ORDER_PAYMENT_RECONCILE: "결제 재조정",
  SETTLEMENT_MARK_PAID: "정산 지급완료",
  PROMOTION_POST_APPROVE: "홍보 게시 승인",
  PROMOTION_POST_REJECT: "홍보 게시 반려",
  PROMOTION_POST_PUBLISH: "홍보 게시 발행",
};

export const AUDIT_TYPE_TONE: Readonly<Record<string, BadgeTone>> = {
  LISTING_TAKEDOWN: "danger",
  POST_REMOVE: "danger",
  COMMENT_HIDE: "warning",
  ACCOUNT_SUSPEND: "danger",
  ACCOUNT_REINSTATE: "success",
  ACCOUNT_ROLE_CHANGE: "brand",
  REPORT_RESOLVE: "success",
  REPORT_DISMISS: "neutral",
  REVIEW_HIDE: "warning",
  COMMENT_REPORT_CONTEXT_VIEW: "neutral",
  CATALOG_SET_CREATE: "brand",
  CATALOG_SET_UPDATE: "brand",
  CATALOG_SET_FEATURE: "brand",
  ORDER_PAYMENT_RECONCILE: "warning",
  SETTLEMENT_MARK_PAID: "success",
  PROMOTION_POST_APPROVE: "success",
  PROMOTION_POST_REJECT: "danger",
  PROMOTION_POST_PUBLISH: "brand",
};

/** ISO 문자열을 목록에서 읽기 좋은 짧은 형식으로. */
export function formatDateTime(value: string | null): string {
  if (value === null) {
    return "—";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "—";
  }
  return date.toLocaleString("ko-KR", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** 목록에서 긴 id를 접두 8자로 줄인다. */
export function shortId(value: string): string {
  return value.length > 8 ? value.slice(0, 8) : value;
}
