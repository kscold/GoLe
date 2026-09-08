"use client";

import { useEffect, useRef, useState } from "react";
import {
  fetchTrackerStatus,
  verifyTrackerConnection,
  queryTrackerSample,
  requeryTrackerShipment,
  type TrackerDiagnostics,
  type TrackerSample,
} from "@gole/core/tracker-admin";

const failures: Record<string, string> = {
  MISSING_CREDENTIALS: "서버 자격증명이 설정되지 않았습니다.",
  DISABLED: "실제 배송 Tracker가 비활성 상태입니다.",
  AUTHENTICATION_FAILED: "자격증명 인증에 실패했습니다. 서버 설정을 확인해 주세요.",
  GRAPHQL_ERROR: "Tracker가 쿼리를 거부했습니다. 인증·사용량·송장 정보를 확인해 주세요.",
  RATE_LIMITED: "조회 간격 제한입니다. 잠시 후 다시 시도해 주세요.",
  PROVIDER_RATE_LIMITED: "Tracker 사용량 제한입니다. 1분 후 다시 시도해 주세요.",
  TIMEOUT: "Tracker 응답 시간이 초과됐습니다.",
  UNKNOWN_STATUS: "지원하지 않는 배송 상태입니다.",
  TRACK_NOT_FOUND: "배송 정보를 찾지 못했습니다.",
  TRACKING_UNAVAILABLE: "배송 상태를 확인할 수 없습니다. 배송 접수나 완료로 판단하지 마세요.",
};
const statusLabels = {
  PENDING: "접수 대기",
  IN_TRANSIT: "배송 중",
  DELIVERED: "배송 완료",
  UNKNOWN: "확인 불가",
};
const carriers = [
  ["cj_logistics", "CJ대한통운"],
  ["post_office", "우체국택배"],
  ["hanjin", "한진택배"],
  ["lotte", "롯데택배"],
  ["logen", "로젠택배"],
] as const;
const field = "w-full rounded-lg border border-neutral-200 bg-white px-3 py-2 text-sm";
const button =
  "rounded-lg bg-brand-600 px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40";
const panel = "space-y-4 rounded-xl border border-neutral-200 bg-white p-5";
const date = (value: string | null) =>
  value ? new Date(value).toLocaleString("ko-KR") : "기록 없음";

export function AdminTrackerPage() {
  const [diagnostics, setDiagnostics] = useState<TrackerDiagnostics | null>(null);
  const [result, setResult] = useState<TrackerSample | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const lock = useRef(false);
  const [carrier, setCarrier] = useState("cj_logistics");
  const [waybill, setWaybill] = useState("");
  const [orderId, setOrderId] = useState("");
  useEffect(() => {
    const controller = new AbortController();
    fetchTrackerStatus(controller.signal)
      .then(setDiagnostics)
      .catch(() => {
        if (!controller.signal.aborted)
          setError("연동 정보를 불러오지 못했습니다. 관리자 권한과 API 배포 상태를 확인해 주세요.");
      });
    return () => controller.abort();
  }, []);
  async function run(action: () => Promise<void>) {
    if (lock.current) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      await action();
    } catch {
      setError(
        "요청을 완료하지 못했습니다. 관리자 권한·입력값을 확인하고 5초 후 다시 시도해 주세요.",
      );
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  const ready = diagnostics?.enabled && diagnostics.configured;
  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <header className="space-y-2">
        <p className="text-sm text-neutral-500">관리자 / 연동</p>
        <h1 className="text-2xl font-semibold">배송 Tracker</h1>
        <p className="text-sm text-neutral-500">
          기존 Delivery Tracker 연결 상태와 배송 조회를 확인합니다.
        </p>
      </header>
      {error && (
        <p role="alert" className="rounded-lg border border-neutral-200 p-4 text-sm">
          {error}
        </p>
      )}
      <section className={panel} aria-labelledby="tracker-readiness">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 id="tracker-readiness" className="font-semibold">
            연동 준비 상태
          </h2>
          <button
            className={button}
            disabled={busy}
            onClick={() => void run(async () => setDiagnostics(await fetchTrackerStatus()))}
          >
            상태 새로고침
          </button>
        </div>
        {diagnostics ? (
          <>
            <dl className="grid gap-4 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-neutral-500">실제 연동</dt>
                <dd>{diagnostics.enabled ? "활성" : "비활성 · 미연결"}</dd>
              </div>
              <div>
                <dt className="text-neutral-500">자격증명</dt>
                <dd>{diagnostics.configured ? "설정됨 · 값 비공개" : "미설정"}</dd>
              </div>
              <div>
                <dt className="text-neutral-500">최근 연결 결과</dt>
                <dd>{diagnostics.connected ? "성공" : "연결 미확인"}</dd>
              </div>
              <div>
                <dt className="text-neutral-500">최근 성공</dt>
                <dd>{date(diagnostics.lastSuccessAt)}</dd>
              </div>
              <div>
                <dt className="text-neutral-500">마지막 실패</dt>
                <dd>{date(diagnostics.lastFailureAt)}</dd>
              </div>
            </dl>
            {diagnostics.lastFailure && (
              <p className="text-sm">
                {failures[diagnostics.lastFailure] ?? "Tracker 요청을 처리하지 못했습니다."} (
                {diagnostics.lastFailure})
              </p>
            )}
            <button
              className={button}
              disabled={busy || !ready}
              onClick={() => void run(async () => setDiagnostics(await verifyTrackerConnection()))}
            >
              연결 검증
            </button>
            <p className="text-xs text-neutral-500">
              택배사 목록으로 인증을 확인합니다. 실제 API 사용량이 발생할 수 있으며 검증은 60초
              간격으로 제한됩니다.
            </p>
          </>
        ) : (
          <p className="text-sm">
            {error ? "연동 상태를 확인할 수 없습니다." : "연동 상태를 불러오는 중…"}
          </p>
        )}
      </section>
      <section className={panel} aria-labelledby="tracker-config">
        <h2 id="tracker-config" className="font-semibold">
          서버 설정 방법
        </h2>
        <p className="text-sm">
          Delivery Tracker Console에서 프로젝트의 Client ID와 Client Secret을 발급한 뒤, API 서버를
          실행하는 환경의 비밀 변수로 아래 항목을 설정하세요.
        </p>
        <pre className="overflow-x-auto rounded-lg bg-neutral-50 p-4 text-xs">
          {
            "SHIPPING_TRACKER_ENABLED=true\nSHIPPING_TRACKER_CLIENT_ID=<서버 비밀 변수>\nSHIPPING_TRACKER_CLIENT_SECRET=<서버 비밀 변수>"
          }
        </pre>
        <p className="text-sm text-neutral-500">
          apps/api/src/main/resources/application.yml이 환경변수를 읽습니다. 배포 환경의 secret 설정
          또는 로컬 API 실행 터미널에 주입하고 API를 재배포해야 반영됩니다. 브라우저·NEXT_PUBLIC
          변수·소스 코드·DB에 저장하지 마세요. 이 화면은 자격증명을 입력받거나 반환하지 않습니다.
        </p>
        <a
          className="text-sm underline"
          href="https://tracker.delivery/docs/authentication"
          target="_blank"
          rel="noreferrer"
        >
          공식 자격증명 발급 안내
        </a>
      </section>
      <div className="grid gap-6 md:grid-cols-2">
        <form
          className={panel}
          onSubmit={(event) => {
            event.preventDefault();
            void run(async () => {
              setResult(await queryTrackerSample(carrier, waybill));
              setDiagnostics(await fetchTrackerStatus());
            });
          }}
        >
          <h2 className="font-semibold">샘플 배송 조회</h2>
          <label className="block space-y-2 text-sm">
            <span>택배사</span>
            <select
              className={field}
              value={carrier}
              onChange={(event) => setCarrier(event.target.value)}
            >
              {carriers.map(([key, name]) => (
                <option key={key} value={key}>
                  {name}
                </option>
              ))}
            </select>
          </label>
          <label className="block space-y-2 text-sm">
            <span>운송장 번호</span>
            <input
              className={field}
              required
              inputMode="numeric"
              pattern="[0-9 -]+"
              maxLength={40}
              value={waybill}
              onChange={(event) => setWaybill(event.target.value)}
              autoComplete="off"
              placeholder="숫자 8~20자리"
            />
          </label>
          <button className={button} disabled={busy || !ready}>
            배송 조회
          </button>
        </form>
        <form
          className={panel}
          onSubmit={(event) => {
            event.preventDefault();
            void run(async () => {
              setResult(await requeryTrackerShipment(orderId));
              setDiagnostics(await fetchTrackerStatus());
            });
          }}
        >
          <h2 className="font-semibold">기존 배송 안전 재조회</h2>
          <label className="block space-y-2 text-sm">
            <span>주문 ID</span>
            <input
              className={field}
              required
              pattern="[a-zA-Z0-9_-]{1,100}"
              maxLength={100}
              value={orderId}
              onChange={(event) => setOrderId(event.target.value)}
              autoComplete="off"
            />
          </label>
          <p className="text-sm text-neutral-500">
            등록된 운송장을 읽어 조회합니다. 주문·배송 상태 변경이나 알림 발송 없이 기존 캐시를
            사용합니다.
          </p>
          <button className={button} disabled={busy || !ready}>
            안전 재조회
          </button>
        </form>
      </div>
      <p className="text-xs text-neutral-500">
        조회는 감사 기록에 남습니다. 실제 API 사용량이 발생할 수 있습니다. 관리자 요청은 5초
        간격이며 캐시를 강제로 우회할 수 없습니다. 미연결 상태에서는 스텁 배송 결과를 표시하지
        않습니다.
      </p>
      {result && (
        <section className={panel} aria-live="polite">
          <h2 className="font-semibold">조회 결과 · {result.live ? "실제 Tracker" : "미연결"}</h2>
          <p>
            {statusLabels[result.status]} · {result.maskedWaybill}
          </p>
          <p className="text-sm text-neutral-500">
            {result.cached ? "캐시 응답 · 최대 10분 전 정보" : "조회 응답"} · 요청 시각{" "}
            {date(result.checkedAt)}
          </p>
          {result.failure && (
            <p role="status">{failures[result.failure] ?? "배송 정보를 확인할 수 없습니다."}</p>
          )}
        </section>
      )}
    </div>
  );
}
