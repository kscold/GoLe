"use client";

import { useCallback, useEffect, useState, type ReactNode } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  fetchMyOrders,
  fetchMySales,
  fetchMySettlements,
  orderStatusLabel,
  type Order,
  type SellerSettlement,
} from "@entities/order";
import { deleteListing, fetchMyListings, type Listing } from "@entities/listing";
import { fetchLaunchConfig, SAFE_LAUNCH_CONFIG, type LaunchConfig } from "@entities/launch";
import { fetchMe, useSession, type Me } from "@entities/user";
import { formatKrw } from "@shared/lib";
import { ApiError } from "@shared/api";
import {
  AlertCircleIcon,
  Badge,
  Button,
  Card,
  Container,
  EmptyState,
  Heading,
  LinkButton,
  PackageIcon,
  ShoppingBagIcon,
  Skeleton,
  Text,
} from "@shared/ui";

/** 정산 표시용 짧은 날짜. 값이 없으면 미정으로 둔다(서버가 아직 시각을 못 정한 경우). */
function formatSettlementDate(iso: string | null): string {
  if (iso === null) return "미정";
  const at = new Date(iso);
  return Number.isNaN(at.getTime())
    ? "미정"
    : `${at.getFullYear()}.${String(at.getMonth() + 1).padStart(2, "0")}.${String(at.getDate()).padStart(2, "0")}`;
}

type Tab = "info" | "orders" | "sales" | "listings";

const TAB_LABEL: Record<Tab, string> = {
  info: "내 정보",
  orders: "구매 내역",
  sales: "판매 관리",
  listings: "내 매물",
};

/**
 * 조회 상태. 실패를 빈 값으로 뭉개지 않는다 — "없음"과 "못 불러옴"은 사용자가 할 행동이
 * 다르다(전자는 등록하러 가고, 후자는 다시 시도한다).
 */
type Load<T> =
  | { readonly status: "loading" }
  | { readonly status: "ready"; readonly data: T }
  | { readonly status: "failed" };

const LOADING = { status: "loading" } as const;
const FAILED = { status: "failed" } as const;

/** 계정 ID는 UUID라 그대로 두면 줄을 넘긴다. 앞뒤만 남기고 원본은 복사로 가져가게 한다. */
function shortenId(id: string): string {
  return id.length <= 20 ? id : `${id.slice(0, 8)}…${id.slice(-6)}`;
}

export function ProfilePage() {
  const router = useRouter();
  const { session, signOut } = useSession();
  // saveSession이 로컬 저장소에 토큰을 비워서 넣으므로(인증은 HttpOnly 쿠키가 담당)
  // 새로고침 이후 이 값은 항상 빈 문자열이다. 빈 문자열을 "미인증"으로 읽으면 안 된다.
  const token = session?.sessionToken ?? "";
  const [tab, setTab] = useState<Tab>("info");
  const [me, setMe] = useState<Load<Me>>(LOADING);
  const [orders, setOrders] = useState<Load<readonly Order[]>>(LOADING);
  const [listings, setListings] = useState<Load<readonly Listing[]>>(LOADING);
  const [sales, setSales] = useState<Load<readonly Order[]>>(LOADING);
  const [settlements, setSettlements] = useState<Load<readonly SellerSettlement[]>>(LOADING);
  const [launch, setLaunch] = useState<LaunchConfig>(SAFE_LAUNCH_CONFIG);
  const [attempt, setAttempt] = useState(0);
  const [copied, setCopied] = useState(false);
  const [pendingListingStop, setPendingListingStop] = useState<string | null>(null);
  const [stoppingListing, setStoppingListing] = useState<string | null>(null);
  const [listingStopError, setListingStopError] = useState<{
    readonly listingId: string;
    readonly message: string;
  } | null>(null);
  const [listingNotice, setListingNotice] = useState<string | null>(null);

  const reload = useCallback(() => {
    setMe(LOADING);
    setOrders(LOADING);
    setListings(LOADING);
    setSales(LOADING);
    setSettlements(LOADING);
    setLaunch(SAFE_LAUNCH_CONFIG);
    setAttempt((n) => n + 1);
  }, []);

  useEffect(() => {
    // 토큰 유무로 막지 않는다 — 막으면 세 요청이 모두 나가지 않아 이메일·구매 내역·내 매물이
    // 영구히 "불러오는 중"에 멈춘다. apiRequest가 쿠키(credentials:"include")로 인증하고
    // 토큰이 있을 때만 Bearer를 덧붙이므로, 빈 토큰으로도 정상 조회된다(AdminShell과 동일).
    if (!session) return;
    const controller = new AbortController();
    const accountId = session.accountId;

    fetchMe(token)
      .then((r) => {
        if (!controller.signal.aborted) setMe({ status: "ready", data: r });
      })
      .catch(() => {
        if (!controller.signal.aborted) setMe(FAILED);
      });

    fetchMyOrders(accountId, controller.signal)
      .then((r) => {
        if (!controller.signal.aborted) setOrders({ status: "ready", data: r });
      })
      .catch(() => {
        if (!controller.signal.aborted) setOrders(FAILED);
      });

    fetchMyListings(controller.signal)
      .then((mine) => {
        if (!controller.signal.aborted) setListings({ status: "ready", data: mine });
      })
      .catch(() => {
        if (!controller.signal.aborted) setListings(FAILED);
      });

    fetchMySales(controller.signal)
      .then((r) => {
        if (!controller.signal.aborted) setSales({ status: "ready", data: r });
      })
      .catch(() => {
        if (!controller.signal.aborted) setSales(FAILED);
      });

    fetchMySettlements(controller.signal)
      .then((r) => {
        if (!controller.signal.aborted) setSettlements({ status: "ready", data: r });
      })
      .catch(() => {
        if (!controller.signal.aborted) setSettlements(FAILED);
      });

    fetchLaunchConfig(controller.signal).then((config) => {
      if (!controller.signal.aborted) setLaunch(config);
    });

    return () => controller.abort();
  }, [session, token, attempt]);

  // 복사 피드백은 잠깐만 남긴다. 언마운트 후 setState를 막으려 타이머를 정리한다.
  useEffect(() => {
    if (!copied) return;
    const timer = window.setTimeout(() => setCopied(false), 1500);
    return () => window.clearTimeout(timer);
  }, [copied]);

  if (!session) {
    return (
      <Container width="sm">
        <div className="flex flex-col items-start gap-4 pt-12 pb-16">
          <Heading level={1}>내 정보</Heading>
          <Text tone="secondary">로그인이 필요합니다.</Text>
          <LinkButton href="/login?returnTo=%2Fprofile">로그인하러 가기</LinkButton>
        </div>
      </Container>
    );
  }

  const accountId = session.accountId;
  const email = me.status === "ready" ? me.data.email : null;
  // 빈 값은 전부 null로 눕힌다 — 아래 `??` 폴백이 빈 문자열은 걸러 주지 못한다.
  const nickname = me.status === "ready" && me.data.nickname ? me.data.nickname : null;
  const showSettlements =
    launch.features.payments || settlements.status !== "ready" || settlements.data.length > 0;
  const settlementDescription = launch.features.partnerPayout
    ? "지급대행사가 지급 가능 시각 이후 자동 처리를 시도합니다"
    : launch.features.payments
      ? "지급 가능 시각 이후 운영자가 송금 결과를 확인합니다"
      : "플랫폼 결제가 닫히기 전에 생성된 정산 이력만 표시합니다";

  function handleSignOut() {
    signOut();
    router.push("/");
  }

  async function copyAccountId() {
    try {
      await navigator.clipboard.writeText(accountId);
      setCopied(true);
    } catch {
      // 클립보드를 못 쓰는 환경(비보안 컨텍스트 등)에서는 조용히 넘긴다 — 값은 화면에 이미 있다.
    }
  }

  async function stopListing(listingId: string) {
    if (stoppingListing !== null) return;
    setStoppingListing(listingId);
    setListingStopError(null);
    setListingNotice(null);
    try {
      await deleteListing(listingId);
      setListings((current) =>
        current.status === "ready"
          ? { status: "ready", data: current.data.filter((listing) => listing.id !== listingId) }
          : current,
      );
      setPendingListingStop(null);
      setListingNotice("매물 판매를 중지했어요.");
    } catch (error) {
      const message =
        error instanceof ApiError && error.code === "LISTING_ORDER_IN_PROGRESS"
          ? "진행 중인 주문이 있어 판매를 중지할 수 없어요."
          : "판매 중지에 실패했어요. 잠시 후 다시 시도해 주세요.";
      setListingStopError({ listingId, message });
    } finally {
      setStoppingListing(null);
    }
  }

  function tabClass(t: Tab) {
    return `flex-1 border-b-2 py-2.5 text-sm font-semibold transition-colors ${
      tab === t
        ? "border-brand-600 text-brand-700"
        : "border-transparent text-neutral-500 hover:border-neutral-300 hover:text-neutral-800"
    }`;
  }

  return (
    <Container width="sm">
      <div className="flex flex-col gap-6 pt-10 pb-16">
        {/* 아바타 + 기본 */}
        <div className="flex items-center gap-4">
          <div className="grid h-14 w-14 shrink-0 place-items-center rounded-full bg-brand-50 text-xl font-extrabold text-brand-700">
            {/* 닉네임이 있으면 그 첫 글자가, 없으면 이메일 첫 글자가 사람이 알아보는 단서다.
                UUID 첫 글자는 의미가 없다. */}
            {(nickname ?? email ?? accountId).slice(0, 1).toUpperCase()}
          </div>
          <div className="flex min-w-0 flex-col gap-1.5">
            {me.status === "loading" ? (
              <Skeleton className="h-7 w-52 rounded-md" />
            ) : (
              <Heading level={2} className="truncate">
                {nickname ?? email ?? shortenId(accountId)}
              </Heading>
            )}
            {/* flex-col의 기본 stretch가 배지를 줄 끝까지 늘린다. 배지는 내용만큼만 차지해야 한다. */}
            <Badge tone={session.role === "ADMIN" ? "brand" : "neutral"} className="self-start">
              {session.role === "ADMIN" ? "관리자" : "일반 회원"}
            </Badge>
          </div>
        </div>

        {/* 탭 */}
        <div className="grid grid-cols-4 border-b border-neutral-200">
          {(["info", "orders", "sales", "listings"] as Tab[]).map((t) => (
            <button key={t} type="button" className={tabClass(t)} onClick={() => setTab(t)}>
              {TAB_LABEL[t]}
            </button>
          ))}
        </div>

        {/* 내 정보 */}
        {tab === "info" && (
          <div className="flex flex-col gap-4">
            <Card padded className="flex flex-col divide-y divide-neutral-100">
              <InfoRow label="닉네임">
                {me.status === "loading" ? (
                  <Skeleton className="h-5 w-48 rounded" />
                ) : me.status === "failed" ? (
                  /* 닉네임과 이메일은 같은 /me 한 번으로 채워진다. 두 줄 모두 실패 안내와
                     재시도 버튼을 띄우면 같은 요청에 대한 경고가 두 번 겹친다. 안내는 바로
                     아래 이메일 줄이 대표로 맡고 여기는 빈자리 표시만 남긴다. */
                  <p className="text-neutral-400">—</p>
                ) : /* null·undefined·빈 문자열을 모두 "아직 없음"으로 본다 — 서버가 필드를
                      생략해도 빈 줄이 아니라 설정 안내가 나와야 한다. */
                me.data.nickname ? (
                  <p className="text-neutral-900">{me.data.nickname}</p>
                ) : (
                  <div className="flex flex-wrap items-center gap-2">
                    <Text tone="secondary" size="sm">
                      아직 설정하지 않았어요
                    </Text>
                    <Link
                      href="/onboarding"
                      className="font-semibold text-brand-700 underline-offset-4 hover:underline"
                    >
                      닉네임 설정하기
                    </Link>
                  </div>
                )}
              </InfoRow>

              <InfoRow label="이메일">
                {me.status === "loading" ? (
                  <Skeleton className="h-5 w-48 rounded" />
                ) : me.status === "failed" ? (
                  <InlineFailure onRetry={reload} />
                ) : (
                  <p className="text-neutral-900">{me.data.email}</p>
                )}
              </InfoRow>

              <InfoRow label="계정 ID">
                <div className="flex flex-wrap items-center gap-2">
                  <p className="font-mono text-sm text-neutral-700" title={accountId}>
                    {shortenId(accountId)}
                  </p>
                  <Button variant="ghost" size="sm" onClick={copyAccountId}>
                    {copied ? "복사됨" : "복사"}
                  </Button>
                </div>
              </InfoRow>

              <InfoRow label="계정 보안">
                <Link
                  href="/profile/security"
                  className="font-semibold text-brand-700 underline-offset-4 hover:underline"
                >
                  비밀번호 변경
                </Link>
              </InfoRow>

              <InfoRow label="개인정보 권리">
                <Link
                  href="/chat?compose=support&category=PRIVACY_ACCESS"
                  className="font-semibold text-brand-700 underline-offset-4 hover:underline"
                >
                  열람·정정·삭제·처리정지 요청
                </Link>
              </InfoRow>
            </Card>

            {/* 헤더에도 로그아웃이 있다. 여기서는 눈에 덜 띄게 두고 오른쪽으로 뺀다. */}
            <div className="flex justify-end">
              <Button variant="ghost" size="sm" onClick={handleSignOut}>
                로그아웃
              </Button>
            </div>
          </div>
        )}

        {/* 구매 내역 */}
        {tab === "orders" && (
          <div className="flex flex-col gap-3">
            {orders.status === "loading" ? (
              [1, 2, 3].map((i) => <Skeleton key={i} className="h-16 w-full rounded-lg" />)
            ) : orders.status === "failed" ? (
              <PanelFailure onRetry={reload} />
            ) : orders.data.length === 0 ? (
              <PanelEmpty
                icon={<ShoppingBagIcon className="h-8 w-8 text-neutral-400" strokeWidth={1.5} />}
                message="구매 내역이 없어요"
              />
            ) : (
              orders.data.map((o) => (
                <Link
                  key={o.id}
                  href={`/orders/${o.id}${o.status === "completed" && launch.features.reviews ? "#review" : ""}`}
                  className="flex items-center justify-between rounded-lg border border-neutral-200 bg-white px-4 py-3.5 hover:bg-neutral-50"
                >
                  <div className="flex flex-col gap-0.5">
                    <span className="font-mono text-xs text-neutral-400">{o.id.slice(0, 8)}</span>
                    <span className="text-base font-semibold tabular-nums text-neutral-900">
                      {formatKrw(o.amount)}
                    </span>
                  </div>
                  <div className="flex shrink-0 flex-col items-end gap-1">
                    <Badge
                      tone={
                        o.status === "completed"
                          ? "success"
                          : o.status === "payment_review" || o.status === "refund_pending"
                            ? "warning"
                            : o.status === "refunded" || o.status === "payment_failed"
                              ? "danger"
                              : "brand"
                      }
                    >
                      {orderStatusLabel(o.status)}
                    </Badge>
                    {o.status === "completed" && launch.features.reviews ? (
                      <span className="text-xs font-semibold text-brand-600">후기 작성</span>
                    ) : null}
                  </div>
                </Link>
              ))
            )}
          </div>
        )}

        {/* 판매 관리 — 받은 주문(발송 대기)과 정산 예정액. 판매자 루프의 진입점이다. */}
        {tab === "sales" && (
          <div className="flex flex-col gap-8">
            <section className="flex flex-col gap-3">
              <div className="flex items-baseline justify-between gap-3">
                <Heading level={2} className="text-lg">
                  받은 주문
                </Heading>
                <Text tone="muted" size="sm">
                  주문을 눌러 운송장을 등록하세요
                </Text>
              </div>
              {sales.status === "loading" ? (
                [1, 2].map((i) => <Skeleton key={i} className="h-16 w-full rounded-lg" />)
              ) : sales.status === "failed" ? (
                <PanelFailure onRetry={reload} />
              ) : sales.data.length === 0 ? (
                <PanelEmpty
                  icon={<PackageIcon className="h-8 w-8 text-neutral-400" strokeWidth={1.5} />}
                  message="아직 받은 주문이 없어요"
                />
              ) : (
                sales.data.map((o) => (
                  <Link
                    key={o.id}
                    href={`/orders/${o.id}`}
                    className="flex items-center justify-between rounded-lg border border-neutral-200 bg-white px-4 py-3.5 hover:bg-neutral-50"
                  >
                    <div className="flex flex-col gap-0.5">
                      <span className="font-mono text-xs text-neutral-400">{o.id.slice(0, 8)}</span>
                      <span className="text-base font-semibold tabular-nums text-neutral-900">
                        {formatKrw(o.amount)}
                      </span>
                    </div>
                    <Badge tone={o.status === "completed" ? "success" : "warning"}>
                      {orderStatusLabel(o.status)}
                    </Badge>
                  </Link>
                ))
              )}
            </section>

            {showSettlements ? (
              <section className="flex flex-col gap-3">
                <div className="flex items-baseline justify-between gap-3">
                  <Heading level={2} className="text-lg">
                    정산
                  </Heading>
                  <Text tone="muted" size="sm">
                    {settlementDescription}
                  </Text>
                </div>
                {settlements.status === "loading" ? (
                  [1, 2].map((i) => <Skeleton key={i} className="h-16 w-full rounded-lg" />)
                ) : settlements.status === "failed" ? (
                  <PanelFailure onRetry={reload} />
                ) : settlements.data.length === 0 ? (
                  <PanelEmpty
                    icon={
                      <ShoppingBagIcon className="h-8 w-8 text-neutral-400" strokeWidth={1.5} />
                    }
                    message="정산 예정 금액이 없어요"
                  />
                ) : (
                  settlements.data.map((row) => (
                    <div
                      key={row.orderId}
                      className="flex items-center justify-between gap-4 rounded-lg border border-neutral-200 bg-white px-4 py-3.5"
                    >
                      <div className="flex min-w-0 flex-col gap-0.5">
                        <Link
                          href={`/orders/${row.orderId}`}
                          className="font-mono text-xs text-neutral-400 hover:text-brand-600"
                        >
                          {row.orderId.slice(0, 8)}
                        </Link>
                        <span className="text-base font-semibold tabular-nums text-neutral-900">
                          {formatKrw(row.payout)}
                        </span>
                        <span className="text-xs text-neutral-500 tabular-nums">
                          거래액 {formatKrw(row.grossAmount)} · 수수료 {formatKrw(row.fee)}
                        </span>
                      </div>
                      <div className="flex shrink-0 flex-col items-end gap-1">
                        <SettlementStatus row={row} />
                      </div>
                    </div>
                  ))
                )}
              </section>
            ) : null}
          </div>
        )}

        {/* 내 매물 */}
        {tab === "listings" && (
          <div className="flex flex-col gap-3">
            <div className="flex justify-end">
              <LinkButton href="/sell" size="sm">
                매물 등록
              </LinkButton>
            </div>
            {listingNotice ? (
              <p
                role="status"
                className="rounded-lg bg-success-soft px-4 py-3 text-sm text-success"
              >
                {listingNotice}
              </p>
            ) : null}
            {listings.status === "loading" ? (
              [1, 2, 3].map((i) => <Skeleton key={i} className="h-16 w-full rounded-lg" />)
            ) : listings.status === "failed" ? (
              <PanelFailure onRetry={reload} />
            ) : listings.data.length === 0 ? (
              <PanelEmpty
                icon={<PackageIcon className="h-8 w-8 text-neutral-400" strokeWidth={1.5} />}
                message="등록한 매물이 없어요"
                action={
                  <LinkButton href="/sell" size="sm">
                    첫 매물 등록하기
                  </LinkButton>
                }
              />
            ) : (
              listings.data.map((l) => {
                const confirmingStop = pendingListingStop === l.id;
                const stopping = stoppingListing === l.id;
                const stopError =
                  listingStopError?.listingId === l.id ? listingStopError.message : null;
                return (
                  <article
                    key={l.id}
                    className="overflow-hidden rounded-lg border border-neutral-200 bg-white"
                  >
                    <Link
                      href={`/listings/${l.id}`}
                      className="flex items-center justify-between px-4 py-3.5 transition-colors hover:bg-neutral-50"
                    >
                      <div className="flex min-w-0 flex-col gap-0.5">
                        <span className="truncate font-medium text-neutral-900">{l.title}</span>
                        <span className="text-sm tabular-nums text-neutral-500">
                          {formatKrw(l.price)}
                        </span>
                      </div>
                      <Badge
                        tone={
                          l.status === "active"
                            ? "success"
                            : l.status === "reserved"
                              ? "warning"
                              : l.status === "sold"
                                ? "neutral"
                                : "danger"
                        }
                      >
                        {l.status === "active"
                          ? "판매중"
                          : l.status === "reserved"
                            ? "예약중"
                            : l.status === "sold"
                              ? "판매완료"
                              : "삭제됨"}
                      </Badge>
                    </Link>
                    {l.status === "active" ? (
                      <div className="border-t border-neutral-100 px-4 py-3">
                        {confirmingStop ? (
                          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
                            <p className="text-sm text-neutral-700">
                              판매를 중지하면 검색에서 사라지고 되돌릴 수 없어요.
                            </p>
                            <div className="flex shrink-0 gap-2">
                              <Button
                                type="button"
                                variant="ghost"
                                size="sm"
                                disabled={stopping}
                                onClick={() => setPendingListingStop(null)}
                              >
                                취소
                              </Button>
                              <Button
                                type="button"
                                variant="danger"
                                size="sm"
                                disabled={stopping}
                                onClick={() => void stopListing(l.id)}
                              >
                                {stopping ? "중지 중…" : "중지하기"}
                              </Button>
                            </div>
                          </div>
                        ) : (
                          <Button
                            type="button"
                            variant="ghost"
                            size="sm"
                            onClick={() => {
                              setPendingListingStop(l.id);
                              setListingStopError(null);
                              setListingNotice(null);
                            }}
                          >
                            판매 중지
                          </Button>
                        )}
                        {stopError ? (
                          <p role="alert" className="mt-2 text-sm text-danger">
                            {stopError}
                          </p>
                        ) : null}
                      </div>
                    ) : null}
                  </article>
                );
              })
            )}
          </div>
        )}
      </div>
    </Container>
  );
}

function SettlementStatus({ row }: Readonly<{ row: SellerSettlement }>) {
  if (row.status === "PAID") {
    return (
      <>
        <Badge tone="success">지급 완료</Badge>
        <span className="text-xs text-neutral-400">{formatSettlementDate(row.paidAt)}</span>
      </>
    );
  }
  if (row.status === "PAYOUT_IN_PROGRESS") {
    return (
      <>
        <Badge tone="brand">지급 처리 중</Badge>
        <span className="text-xs text-neutral-500">중복 지급을 확인하고 있어요</span>
      </>
    );
  }
  if (row.status === "PAYOUT_FAILED") {
    return (
      <>
        <Badge tone="danger">지급 재시도 예정</Badge>
        <span className="text-xs text-neutral-500">
          {formatSettlementDate(row.payoutNextAttemptAt)} 이후
        </span>
      </>
    );
  }
  if (row.status === "PAYOUT_BLOCKED") {
    return (
      <>
        <Badge tone="danger">지급 보류</Badge>
        <span className="text-xs text-neutral-500">운영팀 확인이 필요해요</span>
      </>
    );
  }
  return (
    <>
      <Badge tone="warning">지급 대기</Badge>
      <span className="text-xs text-neutral-500">{formatSettlementDate(row.payableAt)} 이후</span>
    </>
  );
}

function InfoRow({ label, children }: { readonly label: string; readonly children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5 py-3 first:pt-0 last:pb-0">
      <Text tone="muted" size="sm">
        {label}
      </Text>
      {children}
    </div>
  );
}

/** 한 줄짜리 값이 실패했을 때. "불러오는 중"으로 남겨두면 영원히 기다리게 만든다. */
function InlineFailure({ onRetry }: { readonly onRetry: () => void }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Text tone="secondary" size="sm">
        불러오지 못했어요
      </Text>
      <Button variant="ghost" size="sm" onClick={onRetry}>
        다시 시도
      </Button>
    </div>
  );
}

/** 목록 패널이 실패했을 때. 빈 목록과 구분해서 보여준다. */
function PanelFailure({ onRetry }: { readonly onRetry: () => void }) {
  return (
    <EmptyState
      variant="inline"
      icon={<AlertCircleIcon className="h-8 w-8 text-neutral-400" strokeWidth={1.5} />}
      title="불러오지 못했어요"
      action={
        <Button variant="ghost" size="sm" onClick={onRetry}>
          다시 시도
        </Button>
      }
    />
  );
}

function PanelEmpty({
  icon,
  message,
  action,
}: {
  readonly icon: ReactNode;
  readonly message: string;
  readonly action?: ReactNode;
}) {
  return <EmptyState variant="inline" icon={icon} title={message} action={action} />;
}
