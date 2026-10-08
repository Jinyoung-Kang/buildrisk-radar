import Link from "next/link";
import { Fragment, type ReactNode } from "react";
import { SEVERITY } from "@/lib/colors";
import { CLOSE_REASON, STATUS_LABEL } from "@/lib/format";

/** 문장 나누기 — 마침표 · 물음표 · 느낌표 뒤 공백에서 (소수점 12.4% 처럼 뒤가 공백이 아니거나, 날짜처럼 숫자 사이면 나누지 않음). 정규식 뒤보기(lookbehind)는 옛 사파리에서 오류라 쓰지 않음 */
export function splitSentences(text: string): string[] {
  const out: string[] = [];
  const re = /[.!?]\)?\s+/g;
  let start = 0;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    // 날짜 표기(10. 08. 21:07 · 2026. 10. 08.)는 문장 끝이 아님 — 숫자 뒤 마침표 다음이 다시 숫자면 건너뜀
    if (/\d/.test(text[m.index - 1] ?? "") && /\d/.test(text[m.index + m[0].length] ?? "")) continue;
    const end = m.index + m[0].trimEnd().length;
    out.push(text.slice(start, end));
    start = m.index + m[0].length;
  }
  if (start < text.length) out.push(text.slice(start));
  return out.map((s) => s.trim()).filter(Boolean);
}

/**
 * 한 문장 안의 ' · ' 구절은 통째로 다음 줄로 (구절 중간에서 끊지 않음).
 * 짧은 항목의 나열일 때만 — 긴 문장 속 ' · '는 '그리고'(회생 신청 · 개시)라 묶으면 줄이 들쭉날쭉해지고,
 * 괄호 안의 ' · '(점 · 선)를 나누면 괄호가 갈라짐. 그때는 띄어쓰기 줄바꿈(keep-all)만
 */
const PHRASE_MAX = 28;
const balanced = (t: string) => (t.match(/\(/g)?.length ?? 0) === (t.match(/\)/g)?.length ?? 0);
export function Phrases({ text }: { text: string }) {
  const parts = text.split(" · ");
  if (parts.length === 1 || parts.some((p) => p.length > PHRASE_MAX || !balanced(p))) return <>{text}</>;
  return <PhraseList items={parts} />;
}

/** 구절 목록 (JSX 가 섞인 줄) — 각 구절을 통째로 넘기고 ' · ' 로 이음. 빈 항목은 건너뜀 */
export function PhraseList({ items }: { items: ReactNode[] }) {
  const xs = items.filter((x) => x !== null && x !== undefined && x !== false && x !== "");
  return <>{xs.map((x, i) => <Fragment key={i}>{i > 0 && " "}<span className="inline-block">{x}{i < xs.length - 1 && " ·"}</span></Fragment>)}</>;
}

/** 설명 문자열 — 문장마다 줄을 나누고, 문장 안의 ' · ' 구절은 묶어서 */
export function Prose({ text }: { text?: string | null }) {
  if (!text) return null;
  const s = splitSentences(text);
  if (s.length === 1) return <Phrases text={s[0]} />;
  return <>{s.map((x, i) => <span key={i} className="block"><Phrases text={x} /></span>)}</>;
}

/** 설명 칸에 문자열이 오면 Prose 로, JSX 면 그대로 */
const desc = (v: ReactNode) => (typeof v === "string" ? <Prose text={v} /> : v);

/** as: 제목 수준 — 카드 하나가 페이지 전체인 화면(로그인)은 h1 */
export function Card({ title, sub, right, children, className = "", pad = true, as: H = "h2" }:
  { title?: ReactNode; sub?: ReactNode; right?: ReactNode; children: ReactNode; className?: string; pad?: boolean; as?: "h1" | "h2" }) {
  return (
    <section className={`bg-raised rounded-xl shadow-card ${className}`}>
      {(title || right) && (
        <div className="flex flex-wrap items-start justify-between gap-x-3 gap-y-2 px-4 pt-4">
          <div className="min-w-0 flex-1 basis-60">
            {title && <H className="text-sm font-semibold">{title}</H>}
            {sub && <p className="text-xs text-muted mt-0.5">{desc(sub)}</p>}
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
        {sub && <p className="text-sm text-ink2 mt-1">{desc(sub)}</p>}
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
      {sub && <div className="text-xs text-muted mt-0.5">{desc(sub)}</div>}
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
  return <div className="text-sm text-muted py-8 text-center">{desc(children)}</div>;
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
    <div role="radiogroup" aria-label={label} className="inline-flex max-w-full overflow-x-auto bg-line/60 rounded-lg p-0.5 text-xs whitespace-nowrap">
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
    <a className="text-accent hover:underline tabular whitespace-nowrap" target="_blank" rel="noreferrer"
      href={`https://dart.fss.or.kr/dsaf001/main.do?rcpNo=${rceptNo}`}>{children ?? rceptNo}</a>
  );
}

/** 내려받기 (ADR-027) — 같은 출처 /api/v1/export/… · 서버가 파일 이름을 정함 (Content-Disposition) */
export function DownloadLink({ path, children = "CSV" }: { path: string; children?: ReactNode }) {
  return (
    <a href={`/api/v1${path}`} download
      className="inline-flex items-center gap-1 px-2.5 py-1 rounded-md border border-line text-xs text-ink2 hover:bg-line/40 focus-ring whitespace-nowrap">
      <svg aria-hidden="true" viewBox="0 0 16 16" className="w-3.5 h-3.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
        <path d="M8 2.5v7.5M4.75 7 8 10.25 11.25 7M3 13h10" />
      </svg>
      {children}
    </a>
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
