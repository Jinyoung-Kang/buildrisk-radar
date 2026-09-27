import Link from "next/link";
import type { ReactNode } from "react";
import { SEVERITY } from "@/lib/colors";
import { CLOSE_REASON, STATUS_LABEL } from "@/lib/format";

/** as: 제목 수준 — 카드 하나가 페이지 전체인 화면(로그인)은 h1 */
export function Card({ title, sub, right, children, className = "", pad = true, as: H = "h2" }:
  { title?: ReactNode; sub?: ReactNode; right?: ReactNode; children: ReactNode; className?: string; pad?: boolean; as?: "h1" | "h2" }) {
  return (
    <section className={`bg-raised rounded-xl shadow-card ${className}`}>
      {(title || right) && (
        <div className="flex items-start justify-between gap-3 px-4 pt-4">
          <div>
            {title && <H className="text-sm font-semibold">{title}</H>}
            {sub && <p className="text-xs text-muted mt-0.5">{sub}</p>}
          </div>
          {right}
        </div>
      )}
      <div className={pad ? "p-4" : ""}>{children}</div>
    </section>
  );
}

export function PageTitle({ title, sub, right }: { title: ReactNode; sub?: ReactNode; right?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-3 mb-5">
      <div>
        <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
        {sub && <p className="text-sm text-ink2 mt-1">{sub}</p>}
      </div>
      {right}
    </div>
  );
}

export function Stat({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: string }) {
  return (
    <div className="bg-raised rounded-xl shadow-card px-4 py-3">
      <div className="text-xs text-ink2 flex items-center gap-1.5">
        {tone && <span aria-hidden className="inline-block w-2 h-2 rounded-full" style={{ background: tone }} />}
        {label}
      </div>
      <div className="text-2xl font-semibold mt-1">{value}</div>
      {sub && <div className="text-xs text-muted mt-0.5">{sub}</div>}
    </div>
  );
}

/** 심각도 — 색만으로 구분하지 않도록 아이콘 + 글자 */
export function SeverityBadge({ s }: { s?: string | null }) {
  if (!s) return <span className="text-muted">–</span>;
  const d = SEVERITY[s];
  return (
    <span className="inline-flex items-center gap-1 text-xs font-medium px-1.5 py-0.5 rounded border"
      style={{ borderColor: d.color, color: d.color }} title={`심각도 ${s}`}>
      <span aria-hidden>{d.icon}</span>{d.label}
    </span>
  );
}

export function StatusPill({ status, reason }: { status: string; reason?: string | null }) {
  const cls = status === "OPEN" ? "bg-crit/10 text-crit" : status === "ACK" ? "bg-accent/10 text-accent" : "bg-line text-ink2";
  const label = status === "OPEN" ? "열림" : status === "ACK" ? "확인함" : "닫힘";
  return (
    <span className={`text-xs px-1.5 py-0.5 rounded ${cls}`} title={reason ? CLOSE_REASON[reason] ?? reason : undefined}>
      {label}{status === "CLOSED" && reason ? ` · ${CLOSE_REASON[reason] ?? reason}` : ""}
    </span>
  );
}

export function MetricStatus({ status }: { status?: string }) {
  if (!status || status === "OK") return null;
  return <span className="text-[11px] text-muted border border-line rounded px-1 ml-1">{STATUS_LABEL[status] ?? status}</span>;
}

const JOB_STATUS: Record<string, string> = {
  COMPLETED: "완료", STARTING: "시작 중", STARTED: "실행 중", STOPPING: "멈추는 중", STOPPED: "멈춤", FAILED: "실패", ABANDONED: "폐기", UNKNOWN: "알 수 없음",
};

export function JobStatus({ s, resolvedBy }: { s?: string | null; resolvedBy?: number | null }) {
  // 나중에 같은 JobInstance 가 restart 로 완료된 실패·정지는 '해결됨'으로 흐리게 표시
  const resolved = !!resolvedBy && (s === "FAILED" || s === "STOPPED");
  const color = resolved ? "text-muted" : s === "COMPLETED" ? "text-good" : s === "FAILED" ? "text-crit" : s === "STOPPED" ? "text-serious"
    : s === "STARTED" || s === "STARTING" ? "text-accent" : "text-muted";
  const icon = s === "COMPLETED" ? "✓" : s === "FAILED" ? "✕" : s === "STOPPED" ? "■" : s ? "…" : "–";
  // 화면은 한국어(요청 큐의 '완료 · 실패' 와 같은 말), Spring Batch 원래 코드는 툴팁으로
  return (
    <span className={`text-xs font-medium whitespace-nowrap ${color}`} title={s ?? undefined}>{icon} {s ? JOB_STATUS[s] ?? s : "실행 전"}
      {resolved && <span className="block text-[11px] font-normal text-good">→ #{resolvedBy} 재시작으로 해결</span>}
    </span>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <div className="text-sm text-muted py-8 text-center">{children}</div>;
}

export function ErrorBox({ error }: { error: Error | null }) {
  if (!error) return null;
  const e = error as Error & { code?: string; traceId?: string };
  return (
    <div role="alert" className="rounded-lg border border-crit/40 bg-crit/5 text-sm px-3 py-2 my-3">
      <b className="text-crit">{e.code ?? "오류"}</b> {e.message}
      {e.traceId && <span className="text-muted text-xs ml-2">traceId {e.traceId}</span>}
    </div>
  );
}

export function Loading() {
  return <div className="text-sm text-muted py-8 text-center animate-pulse">불러오는 중…</div>;
}

export function Segmented<T extends string>({ value, options, onChange, label }:
  { value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; label: string }) {
  return (
    <div role="radiogroup" aria-label={label} className="inline-flex bg-line/60 rounded-lg p-0.5 text-xs">
      {options.map((o) => (
        <button key={o.value} role="radio" aria-checked={value === o.value} onClick={() => onChange(o.value)}
          className={`px-2.5 py-1 rounded-md focus-ring ${value === o.value ? "bg-raised shadow-card font-medium" : "text-ink2"}`}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function DartLink({ rceptNo, children }: { rceptNo?: string | null; children?: ReactNode }) {
  if (!rceptNo) return <span className="text-muted">–</span>;
  return (
    <a className="text-accent hover:underline tabular" target="_blank" rel="noreferrer"
      href={`https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${rceptNo}`}>{children ?? rceptNo}</a>
  );
}

export function TargetLink({ type, keyCd, name }: { type: string; keyCd: string; name?: string | null }) {
  return (
    <Link className="hover:underline" href={type === "COMPANY" ? `/companies/${keyCd}` : `/regions/${keyCd}`}>
      {name ?? keyCd}
    </Link>
  );
}

/** 목록 아래 페이지 넘김 — "1–200 / 522건". 한 페이지에 다 들어가면 버튼 없이 건수만 */
export function Pager({ total, page, size, onPage, unit = "건" }: { total: number; page: number; size: number; onPage: (p: number) => void; unit?: string }) {
  const pages = Math.max(1, Math.ceil(total / size));
  const from = total === 0 ? 0 : page * size + 1;
  const to = Math.min(total, (page + 1) * size);
  const btn = "px-2 py-1 rounded-md border border-line text-ink2 hover:bg-line/40 disabled:opacity-40 disabled:hover:bg-transparent focus-ring";
  return (
    <div className="flex items-center justify-between gap-3 text-xs text-muted px-4 py-2 border-t border-line">
      <span className="tabular">{pages > 1 ? `${from.toLocaleString()}–${to.toLocaleString()} / ` : ""}{total.toLocaleString()}{unit}</span>
      {pages > 1 && (
        <nav aria-label="페이지" className="flex items-center gap-1">
          <button type="button" className={btn} disabled={page === 0} onClick={() => onPage(page - 1)}>‹ 이전</button>
          <span className="tabular px-1" aria-live="polite">{page + 1} / {pages}</span>
          <button type="button" className={btn} disabled={page >= pages - 1} onClick={() => onPage(page + 1)}>다음 ›</button>
        </nav>
      )}
    </div>
  );
}
