import type { Evidence } from "@/lib/api";
import { DartLink } from "./ui";

const LABEL: Record<string, string> = {
  periodKey: "기간", period: "기간", value: "값", debtRatio: "부채비율(%)", debtRatioYoyPp: "전년 대비(%p)",
  totalLiabilities: "부채총계(원)", totalEquity: "자본총계(원)", operatingIncome: "분기 영업이익(원)",
  interestExpense: "분기 이자비용(원)", interestExpenseSource: "이자비용 원천 계정", operatingCashFlowQtr: "분기 영업현금흐름(원)", unsoldUnits: "미분양(호)",
  unsold3mAgo: "3개월 전(호)", unsold3mChgPct: "3개월 증감률(%)", unsoldPer1kHh: "천 가구당(호)", households: "총가구",
  householdsYear: "가구 기준연도", priceIdx3mChg: "매매지수 3개월 변화(pt)", saleIdx: "매매지수", rceptNo: "접수번호",
  rceptDt: "접수일", reportNm: "보고서명", eventType: "이벤트 유형", matchedKeyword: "걸린 키워드",
};

function cell(k: string, v: unknown) {
  if (v === null || v === undefined || v === "-") return <span className="text-muted">–</span>;
  if (k === "rceptNo") return <DartLink rceptNo={String(v)} />;
  if (typeof v === "number") return <span className="tabular">{new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 4 }).format(v)}</span>;
  return String(v);
}

function isNum(rows: Record<string, unknown>[], c: string) {
  return c !== "rceptNo" && rows.some((r) => typeof r[c] === "number");
}

/** 경보 근거 (NFR-05) — 조건 · 파라미터 · 관측값 · 원천 */
export default function EvidenceView({ ev }: { ev: Evidence }) {
  // 기간 열을 맨 앞으로, 나머지는 나온 순서대로
  const all = Array.from(new Set(ev.observations.flatMap((o) => Object.keys(o))));
  const cols = [...all.filter((c) => c === "periodKey" || c === "period"), ...all.filter((c) => c !== "periodKey" && c !== "period")];
  return (
    <div className="space-y-4 text-sm">
      <p className="leading-relaxed">{ev.message}</p>
      <div className="grid sm:grid-cols-2 gap-3">
        <div className="rounded-lg bg-page px-3 py-2">
          <div className="section-label">조건 (규칙 {ev.ruleCode} v{ev.ruleVersion})</div>
          <div className="font-medium">{ev.condition}</div>
        </div>
        <div className="rounded-lg bg-page px-3 py-2">
          <div className="section-label">파라미터 (임계값)</div>
          <dl className="text-xs grid grid-cols-[auto_1fr] gap-x-3 gap-y-0.5">
            {Object.entries(ev.params).map(([k, v]) => (
              <div key={k} className="contents"><dt className="text-ink2">{k}</dt><dd className="tabular break-all">{Array.isArray(v) ? v.join(", ") : String(v)}</dd></div>
            ))}
          </dl>
        </div>
      </div>
      <div>
        <div className="section-label">관측값</div>
        <div className="table-wrap">
          <table className="data-table compact">
            <thead><tr>
              {cols.map((c) => <th key={c} className={isNum(ev.observations, c) ? "num" : ""}>{LABEL[c] ?? c}</th>)}
            </tr></thead>
            <tbody>
              {ev.observations.map((o, i) => (
                <tr key={i}>
                  {cols.map((c) => <td key={c} className={isNum(ev.observations, c) ? "num" : "whitespace-nowrap"}>{cell(c, o[c])}</td>)}
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
        {ev.calcRunId && <div className="text-[11px] text-muted mt-2">calcRunId {ev.calcRunId}</div>}
      </div>
    </div>
  );
}
