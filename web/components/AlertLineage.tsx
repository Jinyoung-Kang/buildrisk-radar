import { useState } from "react";
import { type Lineage, type LineageStatement } from "@/lib/api";
import { dt } from "@/lib/domain/format";
import { needsEventNames, paramLabel, paramValue } from "@/lib/domain/ruleLabels";
import { useEventNames } from "@/lib/hooks/useEventNames";
import { useApi } from "@/lib/hooks/useApi";
import { DartLink, ErrorBox, Loading, PhraseList, Prose } from "./ui";
import * as alertApi from "@/lib/api/alert";

const BASIS: Record<string, string> = { POINT: "시점", CUM: "누적", QTR: "분기" };
const FS_DIV: Record<string, string> = { CFS: "연결", OFS: "별도" };
const SJ: Record<string, string> = { BS: "재무상태표", IS: "손익계산서", CIS: "포괄손익계산서", CF: "현금흐름표" };
const MATCH: Record<string, string> = { ACCOUNT_ID: "계정 ID", NAME_EXACT: "계정명", NAME_REGEX: "계정명 패턴" };
const RUN: Record<string, string> = { RULE: "규칙 평가", METRIC: "지표 계산", STANDARD: "표준화", JOB: "배치 실행", COLLECT: "통계 수집" };
const nf = new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 });
const n = (v?: number | null) => (v === null || v === undefined ? "–" : nf.format(v));

function Step({ title, sub, children }: { title: string; sub?: string; children: React.ReactNode }) {
  return (
    <li className="relative pl-5 before:absolute before:left-[5px] before:top-2 before:bottom-0 before:w-px before:bg-line last:before:hidden">
      <span aria-hidden="true" className="absolute left-0 top-1.5 w-[11px] h-[11px] rounded-full border-2 border-accent bg-raised" />
      <div className="section-label">{title}{sub && <span className="normal-case font-normal text-muted"> · {sub}</span>}</div>
      <div className="mt-1">{children}</div>
    </li>
  );
}

function Statement({ s, depth = 0 }: { s: LineageStatement; depth?: number }) {
  return (
    <div className={`rounded-lg border border-line px-3 py-2 ${depth ? "bg-page" : ""}`}>
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5">
        <span className="font-medium">{s.stdName ?? s.stdCode}</span>
        <span className="text-xs text-muted"><PhraseList items={[s.stdCode, s.periodKey, FS_DIV[s.fsDiv] ?? s.fsDiv, BASIS[s.basis] ?? s.basis]} /></span>
        <span className="tabular ml-auto">{n(s.amount)}원</span>
      </div>
      <div className={`text-xs mt-0.5 ${s.reproduced ? "text-ink2" : "text-warn"}`}>
        {s.reproduced ? "✓ 지금의 매핑 규칙으로 다시 계산해도 같은 값" : "⚠ 지금의 매핑 규칙으로 다시 계산한 값이 저장된 값과 다름"}
        {s.derivation && <> · {s.derivation}</>}
      </div>
      {s.lines.length > 0 && (
        <div className="table-wrap mt-2">
          <table className="data-table compact">
            <thead><tr><th>원천 줄</th><th className="num">금액(원)</th><th>고른 매핑 규칙</th></tr></thead>
            <tbody>{s.lines.map((l) => (
              <tr key={`${l.sjDiv}${l.lineNo}`}>
                <td title={l.accountId ?? undefined}>{l.accountNm}<span className="sub">{SJ[l.sjDiv] ?? l.sjDiv} · {l.lineNo}번째 줄</span></td>
                <td className="num">{n(l.amount)}</td>
                <td className="text-xs">#{l.mapId} {MATCH[l.matchType] ?? l.matchType}<span className="sub [overflow-wrap:anywhere]">{l.pattern}</span></td>
              </tr>))}</tbody>
          </table>
        </div>
      )}
      {s.derivedFrom.length > 0 && <div className="mt-2 space-y-2">{s.derivedFrom.map((c) => <Statement key={`${c.periodKey}${c.basis}`} s={c} depth={depth + 1} />)}</div>}
      {s.basis !== "QTR" && (
        <div className="text-[11px] text-muted mt-1.5">
          <PhraseList items={[<>원문 <DartLink rceptNo={s.rceptNo} /></>, s.jobExecutionId ? `수집 실행 #${s.jobExecutionId}` : "수집 실행 기록 없음", s.fetchedAt && `받은 시각 ${dt(s.fetchedAt)}`]} />
        </div>
      )}
    </div>
  );
}

function Body({ l }: { l: Lineage }) {
  const names = useEventNames(needsEventNames(l.rule.params));
  return (
    <ol className="space-y-4">
      <Step title="규칙" sub={`${l.rule.code} v${l.rule.version}`}>
        <div>{l.rule.nameKo}</div>
        <div className="flex flex-wrap gap-1 mt-1">
          {Object.entries(l.rule.params).map(([k, v]) => (
            <span key={k} className="rounded bg-page px-1.5 py-0.5 text-xs tabular">{paramLabel(l.rule.code, k)} = {paramValue(k, v, names)}</span>
          ))}
        </div>
        {(l.rule.changeNote || l.rule.createdAt) && <div className="text-xs text-muted mt-1">{l.rule.changeNote}{l.rule.changeNote && l.rule.createdAt && " · "}{l.rule.createdAt && `버전 생성 ${dt(l.rule.createdAt)}`}</div>}
      </Step>
      {l.metrics.length > 0 && (
        <Step title="지표" sub="경보 근거에 들어간 값">
          <div className="table-wrap">
            <table className="data-table compact">
              <thead><tr><th>지표</th><th>대상</th><th>기간</th><th className="num">값</th><th>상태</th></tr></thead>
              <tbody>{l.metrics.map((m) => (
                <tr key={`${m.scope}${m.targetKey}${m.period}${m.metricCode}`}>
                  <td>{m.name ?? m.metricCode}<span className="sub">{m.formula ?? m.metricCode}</span></td>
                  <td className="whitespace-nowrap tabular">{m.targetKey}</td>
                  <td className="whitespace-nowrap tabular">{m.period}</td>
                  <td className="num">{n(m.value)}{m.unit && m.value != null ? ` ${m.unit}` : ""}</td>
                  <td className="whitespace-nowrap text-xs">{m.status}</td>
                </tr>))}</tbody>
            </table>
          </div>
        </Step>
      )}
      {l.statements.length > 0 && (
        <Step title="표준계정 → 원천 재무제표 줄" sub="DART 재무제표 응답의 그 줄과, 그 줄을 고른 매핑 규칙">
          <div className="space-y-2">{l.statements.map((s) => <Statement key={`${s.periodKey}${s.fsDiv}${s.stdCode}${s.basis}`} s={s} />)}</div>
        </Step>
      )}
      {l.stats.length > 0 && (
        <Step title="공공 통계" sub="지역 지표를 만든 원천 행">
          <div className="table-wrap">
            <table className="data-table compact">
              <thead><tr><th>통계</th><th>지역</th><th>기간</th><th className="num">값</th><th>출처 표</th></tr></thead>
              <tbody>{l.stats.map((s) => (
                <tr key={`${s.seriesId}${s.regionCd}${s.period}`}>
                  <td>{s.name}{s.role === "CHILD" && <span className="sub">일반구 행 (시 값은 합 · 평균)</span>}</td>
                  <td className="whitespace-nowrap tabular">{s.regionCd}</td>
                  <td className="whitespace-nowrap tabular">{s.period}</td>
                  <td className="num">{n(s.value)} {s.unit}</td>
                  <td className="text-xs">{s.source} {s.sourceTable}</td>
                </tr>))}</tbody>
            </table>
          </div>
        </Step>
      )}
      {l.filings.length > 0 && (
        <Step title="공시 원문" sub="구조화한 값과 파서 버전">
          <div className="space-y-2">{l.filings.map((f) => (
            <div key={f.rceptNo} className="rounded-lg border border-line px-3 py-2">
              <div className="flex flex-wrap gap-x-2 items-baseline">
                <DartLink rceptNo={f.rceptNo} /><span className="font-medium">{f.reportNm}</span>
                <span className="text-xs text-muted">{f.rceptDt}{f.eventType && ` · ${f.eventType}`}{f.parserVersion != null && ` · 파서 v${f.parserVersion} ${f.parseStatus ?? ""}`}</span>
              </div>
              <dl className="text-xs grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5 mt-1">
                {Object.entries(f.facts).filter(([k, v]) => k !== "summaryRows" && v !== null && v !== undefined).map(([k, v]) => (
                  <div key={k} className="contents"><dt className="text-ink2">{k}</dt><dd className="tabular [overflow-wrap:anywhere]">{typeof v === "number" ? n(v) : String(v)}</dd></div>
                ))}
                {Array.isArray(f.facts.summaryRows) && <div className="contents"><dt className="text-ink2">요약표</dt><dd>{f.facts.summaryRows.length}행 보관</dd></div>}
              </dl>
              <div className="text-[11px] text-muted mt-1">{f.jobExecutionId ? `수집 실행 #${f.jobExecutionId}` : "수집 실행 기록 없음"}{f.fetchedAt && ` · 받은 시각 ${dt(f.fetchedAt)}`}</div>
            </div>))}</div>
        </Step>
      )}
      <Step title="실행" sub="수집 · 계산 · 평가 이력">
        <div className="table-wrap">
          <table className="data-table compact">
            <thead><tr><th>종류</th><th>이름</th><th>상태</th><th>시작 – 끝</th></tr></thead>
            <tbody>{l.runs.map((r) => (
              <tr key={`${r.kind}${r.id}`}>
                <td className="whitespace-nowrap">{RUN[r.kind] ?? r.kind}</td>
                <td>{r.name ?? "–"}<span className="sub break-all">{r.kind === "JOB" ? `#${r.id}` : r.id}</span></td>
                <td className="whitespace-nowrap text-xs">{r.status ?? "–"}</td>
                <td className="whitespace-nowrap text-xs tabular">{dt(r.startedAt)} – {dt(r.finishedAt)}</td>
              </tr>))}</tbody>
          </table>
        </div>
      </Step>
      <li className="pl-5">
        <ul className="text-[11px] text-muted list-disc pl-4 space-y-0.5">{l.notes.map((t) => <li key={t}><Prose text={t} /></li>)}</ul>
        <a className="inline-block mt-2 text-xs text-accent hover:underline" href={`/api/v1/alerts/${l.alertId}/lineage`} download={`lineage-${l.alertId}.json`}>계보 JSON 내려받기</a>
      </li>
    </ol>
  );
}

/** 근거 계보 (ADR-027) — 펼칠 때만 불러옴 (경보 목록 · 상세 응답은 그대로) */
export default function AlertLineage({ id }: { id: number }) {
  const [open, setOpen] = useState(false);
  const { data, error } = useApi(open ? alertApi.lineage(id) : null);
  return (
    <details className="rounded-lg border border-line" onToggle={(e) => setOpen((e.currentTarget as HTMLDetailsElement).open)}>
      <summary className="cursor-pointer px-3 py-2 text-sm font-medium rounded-lg focus-ring">
        근거 계보 <span className="font-normal text-muted">— 원천 재무제표 줄 · 통계 · 공시 · 실행까지</span>
      </summary>
      <div className="px-3 pb-3 pt-1 text-sm">{error ? <ErrorBox error={error} /> : !data ? <Loading /> : <Body l={data} />}</div>
    </details>
  );
}
