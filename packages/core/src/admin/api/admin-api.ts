import { apiRequest } from "../../runtime";
import type { PaymentMethod } from "../../lib";

// ── 타입 ─────────────────────────────────────────────────────

export interface AdminOverview {
  readonly counts: Readonly<Record<string, number>>;
  readonly gmv: number;
  readonly ordersByStatus: Readonly<Record<string, number>>;
  readonly activeListings: number;
  readonly pendingReports: number;
  /** 롤링 배포 중 구버전 API에는 없을 수 있다. */
  readonly unassignedSupportTickets?: number;
  readonly pendingSettlements: number;
  /** 롤링 배포 중 구버전 API에는 없을 수 있다. */
  readonly paymentReadiness?: AdminPaymentReadiness;
}

export interface AdminPaymentConfigurationIssue {
  readonly setting: string;
  readonly problem: "MISSING" | "INVALID";
}

export interface AdminPaymentReadiness {
  readonly enabled: boolean;
  readonly ready: boolean;
  readonly state: "DISABLED" | "MISCONFIGURED" | "READY";
  readonly channelType: "TEST" | "LIVE" | "UNKNOWN";
  /** 지금 열려 있는 결제수단. 롤링 배포 중 구버전 API에는 없을 수 있다. */
  readonly methods?: readonly string[];
  readonly currency: "KRW";
  readonly issues: readonly AdminPaymentConfigurationIssue[];
}

export interface AdminOrder {
  readonly id: string;
  readonly status: string;
  readonly amount: number;
  readonly buyerId: string;
  readonly sellerId: string;
  readonly catalogSetNumber: string | null;
  /** 결제 승인 시 PG가 알려준 결제수단. 결제 전 주문은 null. */
  readonly paymentMethod: PaymentMethod | null;
  readonly createdAt: string | null;
}

export interface PaymentReconciliation {
  readonly orderId: string;
  readonly status: string;
}

export interface AdminSettlement {
  readonly orderId: string;
  readonly sellerId: string;
  readonly grossAmount: number;
  readonly fee: number;
  readonly payout: number;
  readonly feeRate: number;
  readonly status: "PENDING" | "PAYOUT_IN_PROGRESS" | "PAYOUT_FAILED" | "PAYOUT_BLOCKED" | "PAID";
  readonly paymentReference: string | null;
  readonly createdAt: string | null;
  /** 운영 지급 유예가 끝나는 시각. 이 전에는 서버가 지급 완료 처리를 거부한다. */
  readonly payableAt: string | null;
  readonly paidAt: string | null;
  readonly payoutAttempts: number;
  /** 수동 지급 작업을 원자적으로 배정받은 관리자 계정. 자동 지급 선점이면 null. */
  readonly payoutOperatorId: string | null;
  readonly payoutAttemptedAt: string | null;
  readonly payoutNextAttemptAt: string | null;
  readonly payoutError: string | null;
}

export interface AdminLaunchConfig {
  readonly config: {
    readonly stage: 0 | 1 | 2 | 3;
    readonly tradeMode: "DIRECT_CHAT" | "MANUAL_SETTLEMENT" | "PARTNER_PAYOUT";
    readonly features: {
      readonly payments: boolean;
      readonly reviews: boolean;
      readonly partnerPayout: boolean;
    };
    /** 구버전 API의 필드 누락은 false로 해석해 신규 판매를 열지 않는다. */
    readonly sellerIdentityVerificationReady?: boolean;
    /** SMTP 등 이메일 challenge의 완결 수단이 실제로 준비됐을 때만 true다. */
    readonly emailAuthenticationAvailable?: boolean;
    readonly updatedAt: string | null;
  };
  /** 운영자가 저장한 단계. config.stage는 현재 정산 모드로 낮춘 실제 실행 단계다. */
  readonly requestedStage: 0 | 1 | 2 | 3;
  readonly overrides: Readonly<Partial<Record<"payments" | "reviews" | "partnerPayout", boolean>>>;
  /** 구버전 API와 롤링 배포 중에는 없을 수 있어 UI가 미확인으로 fail-closed 한다. */
  readonly readiness?: Readonly<Partial<Record<AdminLaunchReadinessKey, boolean>>>;
  readonly updatedBy: string | null;
  readonly settlementMode: "DISABLED" | "MANUAL" | "PROVIDER";
  readonly payoutContractVerified: boolean;
}

export type AdminLaunchReadinessKey =
  | "businessDisclosure"
  | "termsPrivacy"
  | "paymentFlow"
  | "payoutFlow";

export interface AdminLaunchChange {
  readonly id: string;
  readonly type: "STAGE" | "FEATURE_OVERRIDE" | "READINESS";
  readonly target: string;
  readonly before: string;
  readonly after: string;
  readonly reason: string;
  readonly actorId: string;
  readonly actorEmail: string;
  readonly occurredAt: string;
}

export interface AdminListing {
  readonly id: string;
  readonly title: string;
  readonly sellerId: string;
  readonly price: number;
  readonly status: string;
  readonly category: string | null;
  readonly createdAt: string | null;
}

export interface AdminPost {
  readonly id: string;
  readonly authorId: string;
  readonly content: string;
  readonly type: string;
  readonly status: string;
  readonly createdAt: string | null;
}

export type AdminRole = "USER" | "ADMIN";
export type AdminAccountStatus = "UNVERIFIED" | "VERIFIED" | "SUSPENDED";

export interface AdminAccount {
  readonly id: string;
  readonly email: string;
  readonly role: AdminRole;
  readonly status: AdminAccountStatus;
  readonly lockedUntil: string | null;
  readonly suspendedReason: string | null;
}

export type AdminAccountDeletionStatus = "BLOCKED" | "READY" | "COMPLETED";
export type AdminAccountDeletionBlocker =
  | "ACTIVE_ORDER"
  | "UNSETTLED_PAYOUT"
  | "PENDING_REPORT"
  | "SUPPORT_RECORDS_REQUIRE_PURGE"
  | "PUBLIC_CONTENT_REQUIRES_LIFECYCLE_REVIEW"
  | "MEDIA_REQUIRES_LIFECYCLE_REVIEW"
  | "OWNED_GROUP_REQUIRES_TRANSFER"
  | "EXPLICIT_RETENTION_HOLD";
export type AdminAccountDeletionHoldReason =
  | "LEGAL_OBLIGATION"
  | "DISPUTE_OR_CLAIM"
  | "FRAUD_OR_SECURITY_INVESTIGATION";

/** 탈퇴 대상 이메일/accountId를 의도적으로 포함하지 않는 운영 행. */
export interface AdminAccountDeletionRequest {
  readonly requestId: string;
  readonly status: AdminAccountDeletionStatus;
  readonly blockers: readonly AdminAccountDeletionBlocker[];
  readonly holdReason: AdminAccountDeletionHoldReason | null;
  readonly requestedAt: string;
  readonly updatedAt: string;
  readonly completedAt: string | null;
  readonly deletionCounts: Readonly<Record<string, number>>;
}

export interface AdminReport {
  readonly id: string;
  readonly reporterId: string;
  readonly targetType: "LISTING" | "POST" | "COMMENT" | "REVIEW" | "CHAT_MESSAGE";
  readonly targetId: string;
  readonly reason: string;
  readonly detail: string;
  readonly status: "PENDING" | "RESOLVED" | "DISMISSED";
  readonly createdAt: string | null;
  readonly handledAt: string | null;
}

export interface AdminCommentReportContext {
  readonly id: string;
  readonly postId: string;
  readonly authorId: string;
  readonly content: string;
  readonly createdAt: string;
  readonly hidden: boolean;
}

export type AdminSupportStatus = "UNASSIGNED" | "IN_PROGRESS" | "WAITING_USER" | "RESOLVED";

export type AdminSupportCategory =
  | "GENERAL"
  | "TRADE"
  | "PAYMENT"
  | "PRODUCT_FEEDBACK"
  | "PRIVACY_ACCESS"
  | "PRIVACY_CORRECTION_DELETION"
  | "PRIVACY_PROCESSING_STOP";

export type AdminSupportPriority = "LOW" | "NORMAL" | "HIGH" | "URGENT";

export interface AdminSupportAssistantAnalysis {
  readonly category: AdminSupportCategory;
  readonly priority: AdminSupportPriority;
  readonly summary: string;
  readonly draft: string;
  readonly risk: readonly string[];
  readonly humanReview: boolean;
  readonly externalModel: boolean;
  readonly engine: string;
}

export interface AdminSupportTicket {
  readonly roomId: string;
  readonly requesterId: string;
  readonly title: string;
  readonly category: AdminSupportCategory;
  readonly status: AdminSupportStatus;
  readonly assigneeId: string | null;
  readonly createdAt: string;
  readonly updatedAt: string;
  readonly resolvedAt: string | null;
  readonly progressDueAt: string;
  readonly responseDueAt: string | null;
  readonly assistantAnalysis?: AdminSupportAssistantAnalysis | null;
}

export interface AdminSupportMessage {
  readonly id: string;
  readonly roomId: string;
  readonly senderId: string;
  readonly content: string;
  readonly sentAt: string;
}

export interface AdminSupportNote {
  readonly id: string;
  readonly authorId: string;
  readonly note: string;
  readonly createdAt: string;
}

export interface AdminChatReportSnapshotMessage {
  readonly messageId: string;
  readonly senderId: string;
  readonly content: string;
  readonly sentAt: string;
}

export interface AdminChatReportSnapshot {
  readonly id: string;
  readonly reportId: string;
  readonly roomId: string;
  readonly reportedMessageId: string;
  readonly reporterId: string;
  readonly messages: readonly AdminChatReportSnapshotMessage[];
  readonly capturedAt: string;
}

export interface AdminLegoSet {
  readonly setNumber: string;
  readonly name: string;
  readonly theme: string;
  readonly pieceCount: number;
  readonly releaseYear: number;
  readonly retirementStatus: string;
  readonly imageUrl: string | null;
  readonly featured: boolean;
}

export interface CreateSetInput {
  readonly setNumber: string;
  readonly name: string;
  readonly theme: string;
  readonly pieceCount: number;
  readonly releaseYear: number;
  readonly retirementStatus: "ACTIVE" | "RETIRED";
  readonly imageUrl: string;
  readonly featured: boolean;
}

export type UpdateSetInput = Omit<CreateSetInput, "setNumber">;

/** 감사 로그 한 줄. 조치자 이메일은 조치 시점 스냅샷이다. */
export interface AdminAuditEntry {
  readonly id: string;
  readonly actorId: string;
  readonly actorEmail: string;
  readonly type: string;
  readonly targetType: string;
  readonly targetId: string;
  readonly reason: string | null;
  readonly occurredAt: string | null;
}

// ── 공통 ─────────────────────────────────────────────────────

function auth(token: string): Record<string, string> {
  return token.length > 0 ? { Authorization: `Bearer ${token}` } : {};
}

/** 운영 목록은 항상 조회 시점 데이터여야 한다(요구사항 2.5). */
function get<T>(token: string, path: string): Promise<T> {
  return apiRequest<T>(path, { cache: "no-store", headers: auth(token) });
}

function post<T>(
  token: string,
  path: string,
  body?: unknown,
  headers: Readonly<Record<string, string>> = {},
): Promise<T> {
  return apiRequest<T>(path, { method: "POST", headers: { ...auth(token), ...headers }, body });
}

// ── 대시보드 · 감사 ───────────────────────────────────────────

export function fetchAdminOverview(token: string): Promise<AdminOverview> {
  return get<AdminOverview>(token, "/api/admin/overview");
}

export function fetchAdminAudit(token: string, limit = 50): Promise<readonly AdminAuditEntry[]> {
  return get<readonly AdminAuditEntry[]>(token, `/api/admin/audit?limit=${limit}`);
}

// ── 주문 ─────────────────────────────────────────────────────

export function fetchAdminOrders(
  token: string,
  limit = 30,
  status?: string,
  query?: string,
): Promise<readonly AdminOrder[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (status) params.set("status", status);
  if (query?.trim()) params.set("q", query.trim());
  return get<readonly AdminOrder[]>(token, `/api/admin/orders?${params}`);
}

export function reconcileAdminOrderPayment(
  token: string,
  orderId: string,
): Promise<PaymentReconciliation> {
  return post<PaymentReconciliation>(token, `/api/admin/orders/${orderId}/reconcile-payment`);
}

export function fetchAdminSettlements(
  token: string,
  limit = 30,
  status?: AdminSettlement["status"],
): Promise<readonly AdminSettlement[]> {
  const query = status ? `&status=${status}` : "";
  return get<readonly AdminSettlement[]>(token, `/api/admin/settlements?limit=${limit}${query}`);
}

export function markAdminSettlementPaid(
  token: string,
  orderId: string,
  paymentReference: string,
): Promise<AdminSettlement> {
  return post<AdminSettlement>(token, `/api/admin/settlements/${orderId}/paid`, {
    paymentReference,
  });
}

export function claimAdminSettlement(token: string, orderId: string): Promise<AdminSettlement> {
  return post<AdminSettlement>(token, `/api/admin/settlements/${orderId}/claim`);
}

export function reconcileAdminSettlement(
  token: string,
  orderId: string,
  reason: string,
): Promise<AdminSettlement> {
  return post<AdminSettlement>(token, `/api/admin/settlements/${orderId}/reconcile`, { reason });
}

export function recoverAdminSettlement(
  token: string,
  orderId: string,
  input: {
    readonly alreadyPaid: boolean;
    readonly paymentReference?: string;
    readonly reason: string;
  },
): Promise<AdminSettlement> {
  return post<AdminSettlement>(token, `/api/admin/settlements/${orderId}/recover`, input);
}

// ── 출시 단계 ────────────────────────────────────────────────

export function fetchAdminLaunchConfig(token: string): Promise<AdminLaunchConfig> {
  return get<AdminLaunchConfig>(token, "/api/admin/launch");
}

export function fetchAdminLaunchHistory(
  token: string,
  limit = 50,
): Promise<readonly AdminLaunchChange[]> {
  return get<readonly AdminLaunchChange[]>(token, `/api/admin/launch/history?limit=${limit}`);
}

export function changeAdminLaunchStage(
  token: string,
  stage: 0 | 1 | 2 | 3,
  reason: string,
): Promise<AdminLaunchConfig> {
  return post<AdminLaunchConfig>(token, "/api/admin/launch/stage", { stage, reason });
}

export function setAdminLaunchFeature(
  token: string,
  feature: "payments" | "reviews" | "partnerPayout",
  enabled: boolean | null,
  reason: string,
): Promise<AdminLaunchConfig> {
  return post<AdminLaunchConfig>(token, `/api/admin/launch/features/${feature}`, {
    enabled,
    reason,
  });
}

export function setAdminLaunchReadiness(
  token: string,
  check: AdminLaunchReadinessKey,
  confirmed: boolean,
  reason: string,
): Promise<AdminLaunchConfig> {
  return post<AdminLaunchConfig>(token, `/api/admin/launch/readiness/${check}`, {
    confirmed,
    reason,
  });
}

// ── 매물 ─────────────────────────────────────────────────────

export function fetchAdminListings(
  token: string,
  limit = 30,
  status?: string,
  query?: string,
): Promise<readonly AdminListing[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (status) params.set("status", status);
  if (query?.trim()) params.set("q", query.trim());
  return get<readonly AdminListing[]>(token, `/api/admin/listings?${params}`);
}

export function takedownListing(token: string, listingId: string, reason: string): Promise<void> {
  return post<void>(token, `/api/admin/listings/${listingId}/takedown`, { reason });
}

// ── 커뮤니티 ─────────────────────────────────────────────────

export function fetchAdminPosts(
  token: string,
  limit = 30,
  status?: string,
  query?: string,
): Promise<readonly AdminPost[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (status) params.set("status", status);
  if (query?.trim()) params.set("q", query.trim());
  return get<readonly AdminPost[]>(token, `/api/admin/posts?${params}`);
}

export function removeAdminPost(token: string, postId: string, reason: string): Promise<void> {
  return post<void>(token, `/api/admin/posts/${postId}/remove`, { reason });
}

// ── 신고 ─────────────────────────────────────────────────────

export function fetchAdminReports(
  token: string,
  limit = 30,
  status?: AdminReport["status"],
): Promise<readonly AdminReport[]> {
  const query = status ? `&status=${status}` : "";
  return get<readonly AdminReport[]>(token, `/api/admin/reports?limit=${limit}${query}`);
}

export function resolveAdminReport(token: string, reportId: string): Promise<AdminReport> {
  return post<AdminReport>(token, `/api/admin/reports/${reportId}/resolve`);
}

export function resolveAdminReportTarget(
  token: string,
  reportId: string,
  reason: string,
): Promise<AdminReport> {
  return post<AdminReport>(token, `/api/admin/reports/${reportId}/resolve-target`, { reason });
}

export function dismissAdminReport(token: string, reportId: string): Promise<AdminReport> {
  return post<AdminReport>(token, `/api/admin/reports/${reportId}/dismiss`);
}

/** 신고 시점에 서버가 고정한 최소 문맥만 조회한다. 조회 자체가 감사 로그에 남는다. */
export function fetchAdminChatReportSnapshot(
  token: string,
  reportId: string,
): Promise<AdminChatReportSnapshot> {
  return get<AdminChatReportSnapshot>(token, `/api/admin/reports/${reportId}/chat-snapshot`);
}

/** 댓글은 수정 기능이 없으므로 저장된 불변 원문과 부모 게시글을 신고 문맥으로 조회한다. */
export function fetchAdminCommentReportContext(
  token: string,
  reportId: string,
): Promise<AdminCommentReportContext> {
  return get<AdminCommentReportContext>(token, `/api/admin/reports/${reportId}/comment-context`);
}

// ── 운영 문의 ─────────────────────────────────────────────────

export function fetchAdminSupportTickets(
  token: string,
  status?: AdminSupportStatus,
  category?: AdminSupportCategory,
  limit = 50,
): Promise<readonly AdminSupportTicket[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (status) params.set("status", status);
  if (category) params.set("category", category);
  return get<readonly AdminSupportTicket[]>(token, `/api/admin/support?${params}`);
}

export function assignAdminSupportTicket(
  token: string,
  roomId: string,
): Promise<AdminSupportTicket> {
  return post<AdminSupportTicket>(token, `/api/admin/support/${roomId}/assign`);
}

export function transferAdminSupportTicket(
  token: string,
  roomId: string,
  assigneeId: string,
): Promise<AdminSupportTicket> {
  return post<AdminSupportTicket>(token, `/api/admin/support/${roomId}/transfer`, { assigneeId });
}

export function takeoverAdminSupportTicket(
  token: string,
  roomId: string,
  reason: string,
): Promise<AdminSupportTicket> {
  return post<AdminSupportTicket>(token, `/api/admin/support/${roomId}/takeover`, { reason });
}

export function resolveAdminSupportTicket(
  token: string,
  roomId: string,
): Promise<AdminSupportTicket> {
  return post<AdminSupportTicket>(token, `/api/admin/support/${roomId}/resolve`);
}

export function reopenAdminSupportTicket(
  token: string,
  roomId: string,
): Promise<AdminSupportTicket> {
  return post<AdminSupportTicket>(token, `/api/admin/support/${roomId}/reopen`);
}

export function fetchAdminSupportMessages(
  token: string,
  roomId: string,
  cursor?: {
    readonly beforeSentAt: string;
    readonly beforeId: string;
  },
  limit = 60,
): Promise<readonly AdminSupportMessage[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (cursor !== undefined) {
    params.set("beforeSentAt", cursor.beforeSentAt);
    params.set("beforeId", cursor.beforeId);
  }
  return get<readonly AdminSupportMessage[]>(
    token,
    `/api/admin/support/${encodeURIComponent(roomId)}/messages?${params}`,
  );
}

export function replyAdminSupport(
  token: string,
  roomId: string,
  content: string,
): Promise<AdminSupportMessage> {
  return post<AdminSupportMessage>(token, `/api/admin/support/${roomId}/messages`, { content });
}

export function fetchAdminSupportNotes(
  token: string,
  roomId: string,
): Promise<readonly AdminSupportNote[]> {
  return get<readonly AdminSupportNote[]>(token, `/api/admin/support/${roomId}/notes`);
}

export function addAdminSupportNote(token: string, roomId: string, note: string): Promise<void> {
  return post<void>(token, `/api/admin/support/${roomId}/notes`, { note });
}

// ── 회원 ─────────────────────────────────────────────────────

export function fetchAdminAccounts(
  token: string,
  limit = 30,
  query?: string,
): Promise<readonly AdminAccount[]> {
  const search = query ? `&q=${encodeURIComponent(query)}` : "";
  return get<readonly AdminAccount[]>(token, `/api/admin/accounts?limit=${limit}${search}`);
}

export function suspendAdminAccount(
  token: string,
  accountId: string,
  reason: string,
): Promise<AdminAccount> {
  return post<AdminAccount>(token, `/api/admin/accounts/${accountId}/suspend`, { reason });
}

export function reinstateAdminAccount(token: string, accountId: string): Promise<AdminAccount> {
  return post<AdminAccount>(token, `/api/admin/accounts/${accountId}/reinstate`);
}

export function changeAdminAccountRole(
  token: string,
  accountId: string,
  role: AdminRole,
): Promise<AdminAccount> {
  return post<AdminAccount>(token, `/api/admin/accounts/${accountId}/role`, { role });
}

// ── 회원 탈퇴 보존 검토 ───────────────────────────────────────

export function fetchAdminAccountDeletionRequests(
  token: string,
  status?: AdminAccountDeletionStatus,
): Promise<readonly AdminAccountDeletionRequest[]> {
  const query = status ? `?status=${status}` : "";
  return get<readonly AdminAccountDeletionRequest[]>(
    token,
    `/api/admin/account-deletion-requests${query}`,
  );
}

export function reviewAdminAccountDeletion(
  token: string,
  requestId: string,
): Promise<AdminAccountDeletionRequest> {
  return post<AdminAccountDeletionRequest>(
    token,
    `/api/admin/account-deletion-requests/${requestId}/review`,
  );
}

export function holdAdminAccountDeletion(
  token: string,
  requestId: string,
  reasonCode: AdminAccountDeletionHoldReason,
): Promise<AdminAccountDeletionRequest> {
  return post<AdminAccountDeletionRequest>(
    token,
    `/api/admin/account-deletion-requests/${requestId}/hold`,
    { confirmation: requestId, reasonCode },
  );
}

export function releaseAdminAccountDeletionHold(
  token: string,
  requestId: string,
): Promise<AdminAccountDeletionRequest> {
  return post<AdminAccountDeletionRequest>(
    token,
    `/api/admin/account-deletion-requests/${requestId}/hold/release`,
    { confirmation: requestId },
  );
}

export function completeAdminAccountDeletion(
  token: string,
  requestId: string,
  idempotencyKey: string,
): Promise<AdminAccountDeletionRequest> {
  return post<AdminAccountDeletionRequest>(
    token,
    `/api/admin/account-deletion-requests/${requestId}/complete`,
    { confirmation: requestId, preservationReviewed: true },
    { "Idempotency-Key": idempotencyKey },
  );
}

// ── 카탈로그 ─────────────────────────────────────────────────

export function fetchAdminSets(token: string): Promise<readonly AdminLegoSet[]> {
  return get<readonly AdminLegoSet[]>(token, "/api/admin/catalog/sets");
}

export function createAdminSet(token: string, input: CreateSetInput): Promise<AdminLegoSet> {
  return post<AdminLegoSet>(token, "/api/admin/catalog/sets", input);
}

export function updateAdminSet(
  token: string,
  setNumber: string,
  input: UpdateSetInput,
): Promise<AdminLegoSet> {
  return post<AdminLegoSet>(token, `/api/admin/catalog/sets/${setNumber}`, input);
}

export function setAdminSetFeatured(
  token: string,
  setNumber: string,
  featured: boolean,
): Promise<AdminLegoSet> {
  return post<AdminLegoSet>(token, `/api/admin/catalog/sets/${setNumber}/featured`, { featured });
}

// ── 예외 큐 (shipping-and-fees R7.6) ─────────────────────────

export interface AdminShipmentFacts {
  readonly carrierLabel: string;
  readonly waybillNumber: string;
  readonly status: string;
  readonly rawStatus: string | null;
  readonly registeredAt: string;
  readonly deliveredAt: string | null;
  readonly lastTrackedAt: string | null;
}

export interface AdminExceptionEntry {
  readonly type: string;
  readonly typeLabel: string;
  readonly orderId: string;
  readonly orderStatus: string;
  readonly buyerId: string;
  readonly sellerId: string;
  readonly amount: number;
  readonly since: string;
  readonly reason: string | null;
  readonly disputeDetail: string | null;
  /** 배송 사실(R4.3) — 분쟁 판정의 객관적 근거. 미발송이면 null. */
  readonly shipment: AdminShipmentFacts | null;
}

export function fetchAdminExceptionQueue(token: string): Promise<readonly AdminExceptionEntry[]> {
  return get<readonly AdminExceptionEntry[]>(token, "/api/admin/exception-queue");
}

export function resolveAdminDispute(
  token: string,
  orderId: string,
  resolution: "refund" | "complete",
  note: string,
): Promise<readonly AdminExceptionEntry[]> {
  return post<readonly AdminExceptionEntry[]>(
    token,
    `/api/admin/orders/${orderId}/dispute-resolution`,
    {
      resolution,
      note,
    },
  );
}

export interface AdminOrderContacts {
  readonly buyerPhone: string | null;
  readonly sellerPhone: string | null;
  readonly notice: string;
}

/** 전체 번호 열람 — 서버가 감사 로그를 남긴다(R8.5). */
export function fetchAdminOrderContacts(
  token: string,
  orderId: string,
): Promise<AdminOrderContacts> {
  return get<AdminOrderContacts>(token, `/api/admin/orders/${orderId}/contacts`);
}

// ── 홍보 게시 검토 (promotion-review) ─────────────────────────

export type PromotionChannel = "THREADS";

export type PromotionPostStatus = "DRAFT" | "PENDING_REVIEW" | "APPROVED" | "PUBLISHED";

/**
 * 지금 발행(publish)은 스텁 어댑터가 처리해 실제 Threads에 올라가지 않는다
 * (promotion-review D5). 자격증명 연동 전까지는 승인·발행해도 외부에 실제로 나가지 않는다.
 */
export interface AdminPromotionPost {
  readonly id: string;
  readonly channel: PromotionChannel;
  readonly caption: string;
  readonly mediaUrls: readonly string[];
  readonly authorId: string;
  readonly status: PromotionPostStatus;
  readonly createdAt: string | null;
  readonly submittedAt: string | null;
  readonly reviewerId: string | null;
  readonly reviewedAt: string | null;
  readonly rejectionReason: string | null;
  readonly publishedAt: string | null;
  readonly externalPostId: string | null;
}

export interface CreatePromotionPostInput {
  readonly channel: PromotionChannel;
  readonly caption: string;
  readonly mediaUrls: readonly string[];
}

export function fetchAdminPromotionPosts(
  token: string,
  limit = 30,
  status?: PromotionPostStatus,
): Promise<readonly AdminPromotionPost[]> {
  const params = new URLSearchParams({ limit: String(limit) });
  if (status) params.set("status", status);
  return get<readonly AdminPromotionPost[]>(token, `/api/admin/promotion-posts?${params}`);
}

export function createAdminPromotionPost(
  token: string,
  input: CreatePromotionPostInput,
): Promise<{ readonly id: string }> {
  return post<{ readonly id: string }>(token, "/api/admin/promotion-posts", input);
}

export function submitAdminPromotionPost(
  token: string,
  promotionPostId: string,
): Promise<AdminPromotionPost> {
  return post<AdminPromotionPost>(token, `/api/admin/promotion-posts/${promotionPostId}/submit`);
}

export function approveAdminPromotionPost(
  token: string,
  promotionPostId: string,
): Promise<AdminPromotionPost> {
  return post<AdminPromotionPost>(token, `/api/admin/promotion-posts/${promotionPostId}/approve`);
}

export function rejectAdminPromotionPost(
  token: string,
  promotionPostId: string,
  reason: string,
): Promise<AdminPromotionPost> {
  return post<AdminPromotionPost>(token, `/api/admin/promotion-posts/${promotionPostId}/reject`, {
    reason,
  });
}

/** 승인된 게시물만 발행 가능. 지금은 스텁이라 실제 Threads에는 올라가지 않는다. */
export function publishAdminPromotionPost(
  token: string,
  promotionPostId: string,
): Promise<AdminPromotionPost> {
  return post<AdminPromotionPost>(token, `/api/admin/promotion-posts/${promotionPostId}/publish`);
}
