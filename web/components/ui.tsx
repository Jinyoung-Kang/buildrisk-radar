import Link from "next/link";
import type { ReactNode } from "react";
import { SEVERITY } from "@/lib/colors";
import { CLOSE_REASON, STATUS_LABEL } from "@/lib/format";

export function Card({ title, sub, right, children, className = "", pad = true }:
  { title?: ReactNode; sub?: ReactNode; right?: ReactNode; children: ReactNode; className?: string; pad?: boolean }) {
  return (
    <section className={`bg-raised rounded-xl shadow-card ${className}`}>
      {(title || right) && (
        <div className="flex items-start justify-between gap-3 px-4 pt-4">
          <div>
            {title && <h2 className="text-sm font-semibold">{title}</h2>}
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

export function JobStatus({ s }: { s?: string | null }) {
  const color = s === "COMPLETED" ? "text-good" : s === "FAILED" ? "text-crit" : s === "STOPPED" ? "text-serious"
    : s === "STARTED" || s === "STARTING" ? "text-accent" : "text-muted";
  const icon = s === "COMPLETED" ? "✓" : s === "FAILED" ? "✕" : s === "STOPPED" ? "■" : s ? "…" : "–";
  return <span className={`text-xs font-medium whitespace-nowrap ${color}`}>{icon} {s ?? "실행 전"}</span>;
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
