import type { Evidence } from "@/lib/api";
import { dt } from "@/lib/domain/format";
import { needsEventNames, paramLabel, paramValue } from "@/lib/domain/ruleLabels";
import { useEventNames, type EventNames } from "@/lib/useEventNames";
import { DartLink, Prose } from "./ui";

/** 관측값 열 이름 — 규칙이 남기는 키(영문)를 짧은 한글로. 단위는 괄호 안 */
const LABEL: Record<string, string> = {
  periodKey: "기간", period: "기간", value: "값",
  // 재무 (R-C01 · R-C02 · R-C03)
  debtRatio: "부채비율(%)", debtRatioYoyPp: "전년 대비(%p)", totalLiabilities: "부채총계(원)", totalEquity: "자본총계(원)",
  operatingIncome: "분기 영업이익(원)", interestExpense: "분기 이자비용(원)", interestExpenseSource: "이자비용 계정",
  operatingCashFlowQtr: "분기 영업현금흐름(원)",
  // 공시 (R-C04)
  rceptNo: "접수번호", rceptDt: "접수일", reportNm: "공시 제목", eventType: "공시 유형", matchedKeyword: "분류 키워드",
  // 채무보증 (R-C05)
  equity: "자기자본(원)", totalBalance: "보증 총 잔액(원)", balanceToEquityPct: "자기자본 대비(%)", balanceIsLimit: "잔액이 한도 기준",
  unusedLimit: "미사용 한도(원)", usedBalance: "사용 잔액(원)", guaranteesInWindow: "기간 내 보증 결정(건)", pfGuaranteeInWindow: "그중 PF 보증(원)",
  // PF 우발채무 (R-C06)
  pfGuarantee: "PF 보증(원)", pfDue12m: "12개월 내 만기 PF(원)", pfGuaranteeToEquityPct: "PF 보증 / 자기자본(%)",
  pfDue12mToEquityPct: "12개월 내 만기 / 자기자본(%)", pfBridgeSharePct: "브릿지론 비중(%)", reconciled: "사업장 합 일치", statedMatch: "본문 합계 일치",
  // 지역 (R-R01 · R-R02 · R-R03)
  unsoldUnits: "미분양(호)", unsold3mAgo: "3개월 전 미분양(호)", unsold3mChgPct: "미분양 3개월 증감(%)", unsoldPer1kHh: "천 가구당 미분양(호)",
  households: "총가구", householdsYear: "가구 기준 연도", saleIdx: "매매가격지수", priceIdx3mChg: "지수 3개월 변화(pt)",
  trades: "거래(건)", trades12mAgo: "전년 같은 달 거래(건)", tradeYoyPct: "거래 전년 대비(%)",
  // 수주 × 지역 (R-X01)
  asOf: "기준 분기", contracts: "수주(건)", totalAmount: "수주 합계(원)", riskRegionAmount: "위험 지역 수주(원)", riskRegionSharePct: "위험 지역 비중(%)",
  contract: "계약명", region: "지역", amount: "계약 금액(원)", unsoldPeriod: "미분양 기준 월",
};

/** 같은 키를 규칙마다 다르게 읽어야 할 때 — R-C02 의 value 는 이자보상배율 */
const RULE_LABEL: Record<string, Record<string, string>> = { "R-C02": { value: "이자보상배율(배)" } };

function cell(k: string, v: unknown, names: EventNames) {
  if (v === null || v === undefined || v === "-") return <span className="text-muted">–</span>;
  if (k === "rceptNo") return <DartLink rceptNo={String(v)} />;
  if (k === "eventType") return names[String(v)] ?? String(v);
  if (typeof v === "number") return <span className="tabular">{new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 }).format(v)}</span>;
  return String(v);
}

function isNum(rows: Record<string, unknown>[], c: string) {
  return c !== "rceptNo" && rows.some((r) => typeof r[c] === "number");
}

/** 경보 근거 (NFR-05) — 조건 · 파라미터 · 관측값 · 원천 */
/** run: 이 경보를 마지막으로 평가한 규칙 평가 실행 (경보 상세 API 의 calcJobExecutionId · calcRunAt) */
export type EvalRun = { jobExecutionId?: number | null; at?: string | null };

export default function EvidenceView({ ev, run }: { ev: Evidence; run?: EvalRun }) {
  const names = useEventNames(needsEventNames(ev.params) || ev.observations.some((o) => "eventType" in o));
  // 기간 열을 맨 앞으로, 나머지는 나온 순서대로
  const all = Array.from(new Set(ev.observations.flatMap((o) => Object.keys(o))));
  const cols = [...all.filter((c) => c === "periodKey" || c === "period"), ...all.filter((c) => c !== "periodKey" && c !== "period")];
  return (
    <div className="space-y-4 text-sm">
      <p className="leading-relaxed"><Prose text={ev.message} /></p>
      <div className="grid sm:grid-cols-2 gap-3">
        <div className="rounded-lg bg-page px-3 py-2">
          <div className="section-label">조건 (규칙 {ev.ruleCode} v{ev.ruleVersion})</div>
          <div className="font-medium">{ev.condition}</div>
        </div>
        <div className="rounded-lg bg-page px-3 py-2">
          <div className="section-label">파라미터 (임계값)</div>
          <dl className="text-xs grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
            {Object.entries(ev.params).map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-ink2">{paramLabel(ev.ruleCode, k)}</dt><dd className="tabular break-all">{paramValue(k, v, names)}</dd></div>
            ))}
          </dl>
        </div>
      </div>
      <div>
        <div className="section-label">관측값</div>
        <div className="table-wrap">
          <table className="data-table compact">
            <thead><tr>
              {cols.map((c) => <th key={c} className={isNum(ev.observations, c) ? "num" : ""}>{RULE_LABEL[ev.ruleCode]?.[c] ?? LABEL[c] ?? c}</th>)}
            </tr></thead>
            <tbody>
              {ev.observations.map((o, i) => (
                <tr key={i}>
                  {cols.map((c) => <td key={c} className={isNum(ev.observations, c) ? "num" : "whitespace-nowrap"}>{cell(c, o[c], names)}</td>)}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
      <div>
        <div className="section-label">원천 (NFR-04 추적성)</div>
        <div className="table-wrap">
          <table className="data-table compact">
            <thead><tr><th>유형</th><th>원천</th><th>기간</th></tr></thead>
            <tbody>{ev.sources.map((s, i) => (
              <tr key={i}>
                <td className="font-medium whitespace-nowrap">{String(s.type)}</td>
                <td>{s.rceptNo ? <>접수번호 <DartLink rceptNo={String(s.rceptNo)} /></> : s.table ? String(s.table) : "–"}</td>
                <td className="whitespace-nowrap text-ink2">{s.period ? String(s.period) : "–"}</td>
              </tr>))}</tbody>
          </table>
        </div>
        {ev.calcRunId && (
          <div className="text-[11px] text-muted mt-2" title={`규칙 평가 실행 ID ${ev.calcRunId}`}>
            {run?.at || run?.jobExecutionId
              ? <>규칙 평가 · {dt(run.at)}{run.jobExecutionId ? ` · 배치 실행 #${run.jobExecutionId}` : ""} — 이 경보를 마지막으로 평가한 실행</>
              : <>규칙 평가 실행 {ev.calcRunId.slice(0, 8)}</>}
          </div>
        )}
      </div>
    </div>
  );
}
