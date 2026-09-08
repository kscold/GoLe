"use client";
import { useEffect, useState, type CSSProperties } from "react";
import {
  DESIGN_SCHEMA,
  DEFAULT_DESIGN_TOKENS,
  validDesignTokens,
  designContrast,
  fetchDesignEditor,
  fetchDesignHistory,
  publishDesign,
  restoreDesign,
  type DesignRevision,
  type DesignTokens,
} from "@gole/core/design";
import { ApiError } from "@shared/api";

export function AdminDesignPage() {
  const [restoreSource, setRestoreSource] = useState<number | undefined>(undefined);
  const [current, setCurrent] = useState<DesignRevision | null>(null);
  const [draft, setDraft] = useState<DesignTokens>(DEFAULT_DESIGN_TOKENS);
  const [history, setHistory] = useState<DesignRevision[]>([]);
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState("");
  const [busy, setBusy] = useState(false);
  const [reviewed, setReviewed] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [historyError, setHistoryError] = useState(false);
  useEffect(() => {
    let active = true;
    void Promise.all([fetchDesignEditor(), fetchDesignHistory()])
      .then(([editor, rows]) => {
        if (active) {
          setRestoreSource(undefined);
          setCurrent(editor.current);
          setDraft(editor.current.tokens);
          setHistory(rows);
        }
      })
      .catch(() => {
        if (active) setMessage("디자인 설정을 불러오지 못했습니다. 다시 불러오기를 눌러 주세요.");
      });
    return () => {
      active = false;
    };
  }, []);
  const valid = validDesignTokens(draft);
  const contrast = valid ? designContrast(draft["--color-brand-600"] ?? "#1d4ed8", "#ffffff") : 0;
  const secondaryContrast = valid
    ? designContrast(
        draft["--color-text-secondary"] ?? "#5b524b",
        draft["--color-surface-raised"] ?? "#fcfbf8",
      )
    : 0;
  const dirty =
    current !== null &&
    DESIGN_SCHEMA.some((token) => draft[token.key] !== current.tokens[token.key]);
  async function reload() {
    setBusy(true);
    try {
      const [editor, rows] = await Promise.all([fetchDesignEditor(), fetchDesignHistory()]);
      setRestoreSource(undefined);
      setCurrent(editor.current);
      setDraft(editor.current.tokens);
      setHistory(rows);
      setConflict(false);
      setReviewed(false);
      setHistoryError(false);
      setMessage("최신 게시 값을 불러왔습니다.");
    } catch {
      setMessage("불러오기에 실패했습니다. 다시 시도해 주세요.");
    } finally {
      setBusy(false);
    }
  }
  async function save(source?: number) {
    if (!current || busy || !reviewed || !reason.trim()) return;
    setBusy(true);
    try {
      const next =
        source === undefined
          ? await publishDesign(current.revision, draft, reason)
          : await restoreDesign(current.revision, source, reason);
      setRestoreSource(undefined);
      setCurrent(next);
      setDraft(next.tokens);
      setReason("");
      setReviewed(false);
      setMessage(`게시 완료 · revision ${next.revision}`);
      window.dispatchEvent(new Event("gole:design-published"));
      try {
        setHistory(await fetchDesignHistory());
        setHistoryError(false);
      } catch {
        setHistoryError(true);
      }
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setConflict(true);
        setMessage(
          "다른 관리자가 먼저 변경했습니다. 입력은 유지됩니다. 최신 값을 불러온 뒤 다시 검토해 주세요.",
        );
      } else
        setMessage(
          "게시하지 못했습니다. 연결 상태를 확인하고 최신 값을 불러와 게시 여부를 확인해 주세요.",
        );
    } finally {
      setBusy(false);
    }
  }
  const button =
    "rounded-lg border border-neutral-300 bg-white px-4 py-2 text-sm font-medium disabled:opacity-40";
  return (
    <section className="min-w-0 space-y-6" aria-busy={busy}>
      <header className="space-y-2">
        <p className="text-sm font-medium text-brand-700">사이트 디자인</p>
        <h2 className="text-2xl">디자인 토큰</h2>
        <p className="text-sm text-neutral-600">
          색상·간격·글자 크기를 미리 보고 게시하세요. 편집 내용은 이 화면의 미리보기에만 적용되며,
          게시하면 전체 사이트에 반영됩니다.
        </p>
        <p className="text-xs text-neutral-500">
          {current ? `현재 revision ${current.revision}` : "설정 로딩 대기"} · 기본값: Tailwind 테마
        </p>
      </header>
      <div className="flex flex-wrap gap-2">
        <button className={button} disabled={busy} onClick={() => void reload()}>
          최신 값 다시 불러오기 · 편집 취소
        </button>
        <button
          className={button}
          disabled={!current || busy}
          onClick={() => {
            setDraft(DEFAULT_DESIGN_TOKENS);
            setRestoreSource(0);
            setReviewed(false);
          }}
        >
          기본값 미리보기
        </button>
      </div>
      {message && (
        <p role="status" className="rounded-lg border border-neutral-200 bg-white p-3 text-sm">
          {message}
        </p>
      )}
      <div className="grid min-w-0 gap-6 xl:grid-cols-2">
        <fieldset disabled={!current || busy} className="min-w-0 space-y-3">
          <legend className="mb-3 font-semibold">허용된 테마 변수</legend>
          {DESIGN_SCHEMA.map((token) => (
            <label
              key={token.key}
              className="flex min-w-0 flex-wrap items-center justify-between gap-2 rounded-lg border border-neutral-200 bg-white p-3"
            >
              <span className="min-w-0 text-xs">
                <span className="block break-all font-mono">{token.key}</span>
                <span className="text-neutral-500">
                  {token.kind === "color"
                    ? "6자리 HEX 색상"
                    : `${token.min}–${token.max}${token.unit}`}{" "}
                  · 기본 {token.defaultValue}
                </span>
              </span>
              <input
                aria-label={token.key}
                className="w-28 rounded-md border border-neutral-300 px-2 py-2 text-sm"
                value={draft[token.key] ?? ""}
                maxLength={16}
                onChange={(event) => {
                  setDraft({ ...draft, [token.key]: event.target.value });
                  setRestoreSource(undefined);
                  setReviewed(false);
                }}
              />
            </label>
          ))}
        </fieldset>
        <div className="min-w-0 space-y-4">
          <h3 className="font-semibold">미리보기</h3>
          <div
            data-testid="design-preview"
            style={(valid ? draft : DEFAULT_DESIGN_TOKENS) as CSSProperties}
            className="space-y-4 rounded-xl border border-neutral-200 bg-surface-raised p-[var(--space-card)] text-[length:var(--design-font-size)]"
          >
            <p className="text-xs text-text-secondary">GoLe · 브릭 컬렉션</p>
            <h3 className="text-xl text-brand-800">나만의 브릭을 만나세요</h3>
            <p className="text-text-secondary">
              다음 조립을 위한 새로운 발견. 색상과 간격, 모서리와 글자 크기를 확인하세요.
            </p>
            <button type="button" className="rounded-lg bg-brand-600 px-5 py-3 text-white">
              컬렉션 둘러보기
            </button>
            <div className="rounded-md bg-brand-50 p-3 text-brand-900">
              선택한 브랜드 색상 미리보기
            </div>
            <div className="h-[var(--space-section)] rounded-md border border-dashed border-brand-300 text-center text-xs">
              섹션 간격
            </div>
          </div>
          {!valid && (
            <p role="alert" className="text-sm text-danger">
              허용 형식과 범위를 확인하세요. 미리보기는 기본값을 표시합니다.
            </p>
          )}
          {valid && (
            <p className="text-sm text-neutral-600">
              흰색 버튼 글자 대비 {contrast.toFixed(2)}:1 · 보조 글자 대비{" "}
              {secondaryContrast.toFixed(2)}:1
            </p>
          )}
          {valid && (contrast < 4.5 || secondaryContrast < 4.5) && (
            <p role="alert" className="rounded-lg bg-warning-soft p-3 text-sm">
              대비 경고: 일반 글자 권장 대비 4.5:1보다 낮습니다. 색상을 조정한 뒤 게시하는 것을
              권장합니다.
            </p>
          )}
          <label className="block space-y-2 text-sm">
            <span>변경 사유 (감사 기록, 필수)</span>
            <textarea
              className="w-full rounded-lg border border-neutral-300 bg-white p-3"
              maxLength={300}
              value={reason}
              disabled={busy}
              onChange={(e) => setReason(e.target.value)}
            />
          </label>
          <label className="flex items-start gap-2 text-sm">
            <input
              type="checkbox"
              checked={reviewed}
              disabled={busy || !valid}
              onChange={(e) => setReviewed(e.target.checked)}
            />
            미리보기와 대비를 확인했으며 전체 사이트에 게시합니다.
          </label>
          <button
            className="w-full rounded-lg bg-brand-600 px-4 py-3 font-medium text-white disabled:opacity-40"
            disabled={
              !current || !valid || !dirty || !reviewed || !reason.trim() || busy || conflict
            }
            onClick={() => void save(restoreSource)}
          >
            검토한 테마 게시
          </button>
        </div>
      </div>
      <section className="space-y-3">
        <h3 className="text-lg">게시 이력 · 감사 기록</h3>
        <p className="text-sm text-neutral-600">
          복원은 아래 값을 미리보기에 불러온 후 검토하고 게시합니다. 기존 기록은 유지됩니다.
        </p>
        {historyError && <p role="alert">게시됐지만 이력을 새로 불러오지 못했습니다.</p>}
        {history.length === 0 && (
          <p className="text-sm text-neutral-500">아직 게시 이력이 없습니다.</p>
        )}
        {history.map((row) => (
          <article
            key={row.revision}
            className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-neutral-200 bg-white p-4"
          >
            <div className="min-w-0 text-sm">
              <p className="font-semibold">
                revision {row.revision} · {row.action}
              </p>
              <p className="break-all">{row.reason}</p>
              <p className="break-all text-xs text-neutral-500">
                {row.publishedAt} · 관리자 {row.actorId}
              </p>
            </div>
            <button
              className={button}
              disabled={busy || !current}
              onClick={() => {
                setDraft(row.tokens);
                setRestoreSource(row.revision);
                setReviewed(false);
                setMessage(
                  `revision ${row.revision} 값을 미리보기에 불러왔습니다. 사유를 입력하고 게시하세요.`,
                );
                setReason(`revision ${row.revision} 복원`);
              }}
            >
              이 값 미리보기
            </button>
          </article>
        ))}
        {history.length >= 25 && (
          <button
            className={button}
            disabled={busy}
            onClick={() => {
              setBusy(true);
              void fetchDesignHistory(history.at(-1)?.revision)
                .then((rows) => {
                  setHistory([...history, ...rows]);
                  setHistoryError(false);
                })
                .catch(() => setHistoryError(true))
                .finally(() => setBusy(false));
            }}
          >
            이전 이력 더 보기
          </button>
        )}
      </section>
    </section>
  );
}
