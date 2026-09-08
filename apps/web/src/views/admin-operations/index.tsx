"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import {
  executeOperation,
  fetchOperations,
  type OperationsSnapshot,
  type OperationRun,
} from "@gole/core/operations";
import { useSession } from "@entities/user";

const statusLabel = { RUNNING: "실행 중 · 중복 실행 잠금", SUCCEEDED: "점검 완료", FAILED: "실패" };
function resultLabel(code: string | null) {
  if (!code) return "결과 대기";
  if (/^EXCEPTIONS_[0-9]+$/.test(code))
    return `현재 예외 ${code.slice(11)}건 · 예외큐에서 조치하세요`;
  const labels: Record<string, string> = {
    PAYMENT_READY: "결제 설정 준비됨 · 실결제 연결은 검증하지 않음",
    PAYMENT_DISABLED: "결제 비활성화",
    PAYMENT_MISCONFIGURED: "결제 설정 미완료 · 주문 관리에서 상세 확인",
    DISCORD_CONFIGURED_SENTRY_NOT_INSTRUMENTED:
      "Discord 설정 활성 · 전달 미검증 / Sentry SDK 연결 대기",
    DISCORD_DISABLED_SENTRY_NOT_INSTRUMENTED: "Discord 비활성 / Sentry SDK 연결 대기",
    DIAGNOSTIC_UNAVAILABLE: "의존 서비스 점검 실패 · 복구 후 재시도하세요",
  };
  return labels[code] ?? "진단 결과 확인 필요";
}
export function AdminOperationsView() {
  const { session } = useSession();
  const token = session?.sessionToken;
  const [snapshot, setSnapshot] = useState<OperationsSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<string | null>(null);
  const [selected, setSelected] = useState<{ jobId: string; retry?: OperationRun } | null>(null);
  const [reason, setReason] = useState<"MANUAL_CHECK" | "INCIDENT_REVIEW">("MANUAL_CHECK");
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    if (token === undefined) return;
    let active = true;
    fetchOperations(token)
      .then((data) => {
        if (active) {
          setSnapshot(data);
          setError(null);
        }
      })
      .catch(() => {
        if (active)
          setError("운영 작업을 불러오지 못했습니다. 관리자 권한과 API 연결을 확인해 주세요.");
      });
    return () => {
      active = false;
    };
  }, [token, revision]);
  async function run() {
    if (token === undefined || !selected || pending) return;
    setPending(selected.jobId);
    setError(null);
    try {
      const result = await executeOperation(
        token,
        selected.jobId,
        selected.retry ? "RETRY_FAILED" : reason,
        selected.retry?.id,
      );
      setSnapshot((current) =>
        current ? { ...current, history: [result, ...current.history].slice(0, 100) } : current,
      );
      setSelected(null);
      setRevision((value) => value + 1);
    } catch {
      setError(
        "실행 결과를 확인하지 못했습니다. 새로고침으로 실행 이력을 확인하세요. 진행 중 작업은 중복 실행할 수 없습니다.",
      );
      setRevision((value) => value + 1);
    } finally {
      setPending(null);
    }
  }
  const button =
    "whitespace-nowrap rounded-lg border border-current px-4 py-2 text-sm font-medium disabled:cursor-not-allowed disabled:opacity-40";
  return (
    <section className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">운영 자동화</h1>
          <p className="mt-2 text-sm text-neutral-500">
            커뮤니티·직거래 운영을 위한 안전한 진단과 최근 100건 실행 감사 이력
          </p>
        </div>
        <button
          className={button}
          disabled={!!pending}
          onClick={() => setRevision((value) => value + 1)}
        >
          새로고침
        </button>
      </div>
      <p className="text-sm">
        배치의 예약 실행 이력은 아직 수집하지 않습니다. 아래 이력은 이 화면/API에서 명시적으로
        실행한 진단입니다. 결제·정산·삭제·외부 알림 발송은 각 전용 운영 절차를 사용합니다.
      </p>
      <nav aria-label="관련 운영 화면" className="flex gap-4 text-sm underline">
        <Link href="/admin/exceptions">예외큐</Link>
        <Link href="/admin/orders">주문 관리</Link>
        <Link href="/admin/support">문의 관리</Link>
      </nav>
      {error && (
        <p role="alert" className="rounded-lg border border-red-400 p-4">
          {error}
        </p>
      )}
      {!snapshot && !error && <p role="status">운영 상태를 불러오는 중…</p>}
      <div className="grid gap-4 lg:grid-cols-3">
        {snapshot?.jobs.map((job) => {
          const latest = snapshot.history.find((run) => run.jobId === job.id);
          const running = snapshot.history.some(
            (run) => run.jobId === job.id && run.status === "RUNNING",
          );
          return (
            <article
              key={job.id}
              className="flex flex-col gap-3 rounded-xl border border-neutral-200 bg-white p-5"
            >
              <h2 className="text-lg font-semibold">{job.title}</h2>
              <p className="text-sm">{job.description}</p>
              <p className="text-sm font-medium">
                {latest
                  ? resultLabel(latest.resultCode)
                  : "미점검 · 실행하여 현재 상태를 확인하세요"}
              </p>
              <p className="text-xs text-neutral-500">
                마지막 실행: {latest ? new Date(latest.startedAt).toLocaleString("ko-KR") : "없음"}
              </p>
              <button
                className={button + " mt-auto"}
                disabled={!!pending || running}
                onClick={() => setSelected({ jobId: job.id })}
              >
                {running ? "실행 잠금 중" : "점검 실행"}
              </button>
            </article>
          );
        })}
      </div>
      {selected && (
        <div
          role="region"
          aria-label="실행 확인"
          className="space-y-3 rounded-xl border border-neutral-200 p-5"
        >
          <h2 className="font-semibold">
            {snapshot?.jobs.find((job) => job.id === selected.jobId)?.title}{" "}
            {selected.retry ? "재시도" : "실행"} 확인
          </h2>
          <p className="text-sm">관리자 ID, 실행 사유와 결과가 감사 이력에 저장됩니다.</p>
          {!selected.retry && (
            <label className="flex gap-3">
              실행 사유
              <select
                className="rounded border border-neutral-200 bg-white p-1"
                value={reason}
                onChange={(event) => setReason(event.target.value as typeof reason)}
              >
                <option value="MANUAL_CHECK">수동 점검</option>
                <option value="INCIDENT_REVIEW">장애 조사</option>
              </select>
            </label>
          )}
          <div className="flex gap-3">
            <button className={button} disabled={!!pending} onClick={() => void run()}>
              {pending ? "실행 중…" : "확인 후 실행"}
            </button>
            <button className={button} disabled={!!pending} onClick={() => setSelected(null)}>
              취소
            </button>
          </div>
        </div>
      )}
      <div className="space-y-3">
        <h2 className="text-xl font-semibold">실행 이력</h2>
        {snapshot?.history.length === 0 && <p className="text-sm">아직 실행한 작업이 없습니다.</p>}
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead>
              <tr>
                {["작업 / 실행 ID", "시각 / 관리자", "상태 / 결과", "조치"].map((label) => (
                  <th key={label} className="border-b border-neutral-200 p-3">
                    {label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {snapshot?.history.map((run) => (
                <tr key={run.id}>
                  <td className="border-b border-neutral-200 p-3">
                    {snapshot.jobs.find((job) => job.id === run.jobId)?.title}
                    <div className="text-xs">{run.id}</div>
                    {run.retryOf && <div className="text-xs">재시도 원본: {run.retryOf}</div>}
                  </td>
                  <td className="border-b border-neutral-200 p-3">
                    {new Date(run.startedAt).toLocaleString("ko-KR")}
                    <div>
                      {run.actorId} · {run.reasonCode}
                    </div>
                  </td>
                  <td className="border-b border-neutral-200 p-3">
                    {statusLabel[run.status]}
                    <div>{resultLabel(run.resultCode)}</div>
                  </td>
                  <td className="border-b border-neutral-200 p-3">
                    {run.status === "FAILED" && (
                      <button
                        className={button}
                        disabled={!!pending}
                        onClick={() => setSelected({ jobId: run.jobId, retry: run })}
                      >
                        재시도
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <aside className="space-y-2 rounded-xl border border-neutral-200 p-5">
        <h2 className="font-semibold">Sentry → Discord 연결 준비</h2>
        <p className="text-sm">
          GoLe 대상 조직·프로젝트가 미확정이므로 외부 설정은 차단 상태입니다. Sentry SDK는 아직
          연결되지 않았습니다. 조직·프로젝트·대상 Discord 채널을 확정한 후 공식 Discord 통합과
          환경별 오류 알림 규칙을 설정해야 합니다. 기존 가입·결제·문의 알림은 기존 Discord 경로를
          유지합니다.
        </p>
        <p className="text-sm">
          오류는 production의 error/fatal만, 같은 이슈는 5분 간격으로 제한하는 정책을 준비했습니다.
          실제 외부 연동 상태와 전달 성공은 이 점검에서 확인하지 않습니다.
        </p>
      </aside>
    </section>
  );
}
