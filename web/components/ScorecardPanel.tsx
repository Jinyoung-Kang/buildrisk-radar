import Link from "next/link";
import { useMemo, useState } from "react";
import { Card, DartLink, Empty, ErrorBox, Loading, Prose, Segmented } from "@/components/ui";
import { type ScInterval, type ScOutcome, type ScRule } from "@/lib/api";
import { num } from "@/lib/domain/format";
import { useApi } from "@/lib/hooks/useApi";
import { bestRule, episodesOf, ruleCodesOf } from "@/lib/domain/scorecard";
import * as insightApi from "@/lib/api/insight";

const months = (days?: number | null) => (days == null ? "–" : `${num(days / 30.44, 1, true)}개월`);
const pct0 = (v?: number | null) => (v == null ? "–" : `${num(v * 100, 0)}%`);
const OUTCOME: Record<ScOutcome, string> = { HIT: "적중", MISS: "빗나감", PENDING: "판정 보류", AFTER_EVENT: "사건 뒤" };

/** 정밀도 막대: 점 = 추정값, 선 = Wilson 95% 구간, 세로 눈금 = 기준선(아무 기업이나 골랐을 때) — 숫자는 옆에 함께 씀 */
function PrecisionBar({ p, ci, base }: { p?: number; ci?: ScInterval; base?: number }) {
  if (p == null || !ci) return <span className="text-muted text-xs">판정 가능한 경보 없음</span>;
  const x = (v: number) => `${Math.min(100, Math.max(0, v * 100))}%`;
  return (
    <div className="relative h-4 w-28 sm:w-40 rounded bg-line/40" role="img"
      aria-label={`정밀도 ${pct0(p)}, 95% 구간 ${pct0(ci.low)}~${pct0(ci.high)}${base != null ? `, 기준선 ${num(base * 100, 1)}%` : ""}`}>
      <span className="absolute top-1/2 h-0.5 -translate-y-1/2 bg-accent/60" style={{ left: x(ci.low), width: `calc(${x(ci.high)} - ${x(ci.low)})` }} />
      <span className="absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full bg-accent ring-2 ring-raised" style={{ left: x(p) }} />
      {base != null && <span className="absolute top-0 h-4 w-0.5 bg-crit" style={{ left: x(base) }} title={`기준선 ${num(base * 100, 1)}%`} />}
    </div>
  );
}

function RuleRow({ r, name, base, h }: { r: ScRule; name: string; base?: number; h: number }) {
  const any = r.ruleCode === "ANY";
  return (
    <tr className={any ? "bg-page font-medium" : ""}>
      <td className="min-w-[9rem]">{!any && <b className="tabular mr-1">{r.ruleCode}</b>}{name}
        <span className="sub">경보 {r.episodes}건 · 적중 {r.hits} · 빗나감 {r.misses} · 보류 {r.pending}{r.afterEvent ? ` · 사건 뒤 ${r.afterEvent}` : ""}</span></td>
      <td>
        <div className="flex items-center gap-2">
          <PrecisionBar p={r.precision} ci={r.precisionCi} base={base} />
          <span className="tabular whitespace-nowrap">{pct0(r.precision)}
            <span className="sub">{r.hits}/{r.hits + r.misses}{r.precisionCi ? ` · ${pct0(r.precisionCi.low)}~${pct0(r.precisionCi.high)}` : ""}</span></span>
        </div>
      </td>
      <td className="num hidden md:table-cell">{r.lift != null ? `${num(r.lift, 1, true)}배` : "–"}</td>
      <td className="num whitespace-nowrap">{r.caught}/{r.events}<span className="sub">{pct0(r.recall)}</span></td>
      <td className="num whitespace-nowrap hidden sm:table-cell" title={`사건 전 ${h}개월 안에 잡은 사건들의 첫 경보 → 사건 중앙값`}>{months(r.medianLeadDays)}</td>
    </tr>
  );
}

/** 경보 성적표 (ADR-023) — 경보 검증 화면의 첫 탭 */
export default function ScorecardPanel() {
  const [h, setH] = useState<"6" | "12" | "24">("12");
  const [outcome, setOutcome] = useState<"" | ScOutcome>("MISS");
  const sc = useApi(insightApi.scorecard(h));
  const { error } = sc;
  // 새 기간을 불러오는 동안 이전 기간의 숫자를 흐리게 남김 — 기간 이름도 보이는 숫자의 기간으로 (VERIFICATION 103)
  const data = sc.data ?? sc.stale;
  const shown = data ? data.horizonMonths : Number(h);
  const loadingNew = !sc.data && !!sc.stale;
  const ruleCodes = useMemo(() => ruleCodesOf(data), [data]);
  const any = data?.rules.find((r) => r.ruleCode === "ANY");
  const best = useMemo(() => bestRule(data), [data]);
  const catchOf = (rule: string, corp: string) => data?.catches.find((c) => c.ruleCode === rule && c.corpCode === corp);
  const episodes = episodesOf(data, outcome);
  return (
    <>
      <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
        <p className="text-sm text-ink2 max-w-3xl">재무 규칙이 실제 부실 사건(회생 · 워크아웃 · 부도 · 감사의견 비적정)을 <b>얼마나 미리, 얼마나 정확히</b> 잡았는지 —
          규칙을 전체 기간에 대해 그 당시 알 수 있던 공시로만 다시 평가해 셉니다.</p>
        <Segmented label="판정 기간" value={h} onChange={setH} options={[{ value: "6", label: "6개월" }, { value: "12", label: "12개월" }, { value: "24", label: "24개월" }]} />
      </div>
      <ErrorBox error={error} />
      {!data ? <Loading /> : (
        <div className={`space-y-5 transition-opacity ${loadingNew ? "opacity-50" : ""}`} aria-busy={loadingNew}>
          <div className="grid sm:grid-cols-3 gap-3">
            <div className="bg-raised rounded-xl shadow-card px-4 py-3">
              <div className="text-xs text-ink2">부실 사건을 미리 잡음 (재무 규칙 중 하나라도)</div>
              <div className="text-2xl font-semibold tabular mt-0.5">{any ? `${any.caught} / ${any.events}건` : "–"}</div>
              <div className="text-[11px] text-muted">사건 전 {shown}개월 안에 경보가 있었던 사건</div>
            </div>
            <div className="bg-raised rounded-xl shadow-card px-4 py-3">
              <div className="text-xs text-ink2">첫 경보 → 사건 (중앙값)</div>
              <div className="text-2xl font-semibold tabular mt-0.5">{months(any?.medianLeadDays)}</div>
              <div className="text-[11px] text-muted">잡은 사건들에서, 경보가 처음 울린 날부터</div>
            </div>
            <div className="bg-raised rounded-xl shadow-card px-4 py-3">
              <div className="text-xs text-ink2">가장 정밀한 규칙{best ? ` · ${best.ruleCode}` : ""}</div>
              <div className="text-2xl font-semibold tabular mt-0.5">{best ? pct0(best.precision) : "–"}
                {best?.lift != null && <span className="text-sm font-normal text-ink2"> · 기준선의 {num(best.lift, 1)}배</span>}</div>
              <div className="text-[11px] text-muted">{best ? `경보가 울린 기업 ${best.hits + best.misses}곳 중 ${best.hits}곳이 ${shown}개월 안에 부실 (기준선 ${num((data.baseRate ?? 0) * 100, 1)}%)` : "판정 가능한 경보 없음"}</div>
            </div>
          </div>

          <Card title="규칙별 성적" sub={`정밀도 = 경보 뒤 ${shown}개월 안에 부실로 이어진 비율 (점 · 선 = 95% 구간, 빨간 눈금 = 기준선) · 재현율 = 사건 전에 경보가 있었던 비율`} pad={false}>
            <div className="table-wrap">
              <table className="data-table">
                <thead><tr><th>규칙</th><th>정밀도</th><th className="num hidden md:table-cell">기준선 대비</th><th className="num">재현율</th>
                  <th className="num hidden sm:table-cell">선행 (중앙값)</th></tr></thead>
                <tbody>{data.rules.map((r) => <RuleRow key={r.ruleCode} r={r} name={data.ruleNames[r.ruleCode] ?? r.ruleCode} base={data.baseRate} h={shown} />)}</tbody>
              </table>
            </div>
            <div className="text-xs text-muted px-4 py-2 border-t border-line">
              관측 {data.observedFrom ?? "–"} ~ {data.observedTo} · 기준선 {num((data.baseRate ?? 0) * 100, 1, true)}% (기업 × 분기 말 표본 {data.baseSamples.toLocaleString()}개)
            </div>
          </Card>

          <Card title={`부실 사건 ${data.events.length}건과 그 전의 경보`} sub="표 안 숫자 = 그 규칙의 첫 경보부터 사건까지 · – = 사건 전에 경보 없음" pad={false}>
            {data.events.length === 0 ? <Empty>관측 기간에 부실 사건이 없습니다 — disclosureSyncJob 으로 공시를 백필하세요.</Empty> : (
              <div className="table-wrap">
                <table className="data-table">
                  <thead><tr><th>기업</th><th>사건일</th><th className="hidden md:table-cell">공시</th>
                    {ruleCodes.map((c) => <th key={c} className="num">{c}</th>)}</tr></thead>
                  <tbody>{data.events.map((e) => (
                    <tr key={e.corpCode}>
                      <td className="whitespace-nowrap"><Link href={`/companies/${e.corpCode}?tab=financials`} className="hover:underline font-medium">{e.corpName}</Link>
                        <span className="sub">{e.kind}</span></td>
                      <td className="tabular whitespace-nowrap">{e.date}</td>
                      <td className="hidden md:table-cell text-xs"><DartLink rceptNo={e.rceptNo}>{e.reportNm.replace(/\s+/g, " ")}</DartLink></td>
                      {ruleCodes.map((c) => {
                        const k = catchOf(c, e.corpCode);
                        return <td key={c} className="num whitespace-nowrap">{!k?.evaluable ? <span className="text-muted">평가 불가</span>
                          : k.caught ? <span className="text-good" title={`첫 경보 ${k.firstSignal}`}>✓ {months(k.leadDays)}</span> : <span className="text-muted">–</span>}</td>;
                      })}
                    </tr>))}</tbody>
                </table>
              </div>
            )}
          </Card>

          <Card title="판정 내역" sub="같은 기업 · 규칙이 분기마다 반복한 경보는 하나로 묶어 셉니다. '빗나감'은 경보가 울렸지만 기간 안에 부실이 없었던 경우"
            right={<Segmented label="판정" value={outcome} onChange={setOutcome}
              options={[{ value: "MISS", label: "빗나감" }, { value: "HIT", label: "적중" }, { value: "PENDING", label: "보류" }, { value: "", label: "전체" }]} />} pad={false}>
            {episodes.length === 0 ? <Empty>해당하는 경보가 없습니다.</Empty> : (
              <div className="table-wrap max-h-[50vh]">
                <table className="data-table compact">
                  <thead><tr><th>규칙</th><th>기업</th><th>경보 기간</th><th className="num">분기</th><th>판정</th><th className="hidden sm:table-cell">사건일</th></tr></thead>
                  <tbody>{episodes.slice(0, 300).map((e) => (
                    <tr key={`${e.ruleCode}-${e.corpCode}-${e.start}`}>
                      <td className="tabular whitespace-nowrap">{e.ruleCode}</td>
                      <td className="whitespace-nowrap"><Link href={`/companies/${e.corpCode}?tab=financials`} className="hover:underline">{data.companyNames[e.corpCode] ?? e.corpCode}</Link></td>
                      <td className="tabular whitespace-nowrap text-ink2">{e.start}{e.last !== e.start ? ` ~ ${e.last}` : ""}</td>
                      <td className="num">{e.signals}</td>
                      <td className={`whitespace-nowrap ${e.outcome === "HIT" ? "text-good" : e.outcome === "MISS" ? "text-ink2" : "text-muted"}`}>
                        {OUTCOME[e.outcome]}{e.leadDays != null ? ` · ${months(e.leadDays)} 전` : ""}</td>
                      <td className="tabular whitespace-nowrap hidden sm:table-cell">{e.eventDate ?? "–"}</td>
                    </tr>))}</tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid md:grid-cols-2 gap-5">
            <Card title="방법"><ul className="list-disc pl-5 space-y-1 text-sm text-ink2">{data.method.map((m) => <li key={m}><Prose text={m} /></li>)}</ul></Card>
            <Card title="한계 (결과를 읽기 전에)"><ul className="list-disc pl-5 space-y-1 text-sm text-ink2">{data.limitations.map((m) => <li key={m}><Prose text={m} /></li>)}</ul></Card>
          </div>
          <p className="text-[11px] text-muted"><Prose text={data.disclaimer} /></p>
        </div>
      )}
    </>
  );
}
