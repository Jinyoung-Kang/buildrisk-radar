import Link from "next/link";
import { useState } from "react";
import { Card, DartLink, Empty, ErrorBox, Loading, Segmented } from "@/components/ui";
import type { CompanyFilings as Filings, ContractRow } from "@/lib/api";
import { eok, num } from "@/lib/format";
import { useApi } from "@/lib/useApi";

const MATCH: Record<string, string> = { SIGUNGU: "", SIDO: "시도만", OVERSEAS: "해외", UNKNOWN: "주소 해석 불가", NONE: "지역 없음" };

function state(c: ContractRow) {
  if (c.terminatedBy) return { label: "해지", cls: "border-crit text-crit" };
  if (c.supersededBy) return { label: "정정됨", cls: "border-line text-muted" };
  return { label: c.correction ? "현재(정정)" : "현재", cls: "border-good text-good" };
}

/** 수주 계약 · 채무보증 — 공시 원문 구조화 결과 (ADR-016) */
export default function CompanyFilings({ corp, riskTh = 5 }: { corp: string; riskTh?: number }) {
  const { data, error } = useApi<Filings>(`/companies/${corp}/filings`);
  const [view, setView] = useState<"current" | "all">("current");
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  const contracts = data.contracts.filter((c) => view === "all" || (!c.supersededBy && !c.terminatedBy));
  const cur = data.contracts.filter((c) => !c.supersededBy && !c.terminatedBy);
  const mapped = cur.filter((c) => c.regionCd && c.amount);
  const total = mapped.reduce((a, c) => a + (c.amount ?? 0), 0);
  const risky = mapped.filter((c) => (c.unsoldPer1kHh ?? 0) >= riskTh).reduce((a, c) => a + (c.amount ?? 0), 0);
  const g = data.guarantees.filter((x) => !x.supersededBy);
  const p = data.parse;
  return (
    <div className="space-y-5">
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
        <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">현재 수주 계약</div>
          <div className="text-lg font-semibold tabular">{cur.length}건 · {eok(cur.reduce((a, c) => a + (c.amount ?? 0), 0))}</div></div>
        <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">미분양 {riskTh}호/천가구 이상 지역 비중</div>
          <div className="text-lg font-semibold tabular">{total ? `${num((risky / total) * 100)}%` : "–"}</div>
          <div className="text-[11px] text-muted">시군구로 매핑된 {mapped.length}건 기준</div></div>
        <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">최근 채무보증 잔액 / 자기자본</div>
          <div className="text-lg font-semibold tabular">{g[0]?.balanceToEquityPct != null ? `${num(g[0].balanceToEquityPct)}%` : "–"}</div>
          <div className="text-[11px] text-muted">{g[0] ? `${g[0].rceptDt} 공시 · 잔액 ${eok(g[0].totalBalance)}${g[0].balanceIsLimit ? " (한도 기준" + (g[0].usedBalance != null ? `, 사용 ${eok(g[0].usedBalance)}` : "") + ")" : ""}` : "보증 공시 없음"}</div></div>
        <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">원문 구조화</div>
          <div className="text-lg font-semibold tabular">{p.parsed}건</div>
          <div className="text-[11px] text-muted">일부만 {p.partial} · 원문 없음 {p.noDoc} · 실패 {p.failed}</div></div>
      </div>

      <Card title="수주 계약 (단일판매ㆍ공급계약)" sub="공시 원문의 계약금액·지역·기간. 지역은 '판매ㆍ공급지역' 주소를 시군구로 해석, 위험 표시는 그 지역의 최신 천 가구당 미분양"
        right={<Segmented label="보기" value={view} onChange={setView} options={[{ value: "current", label: "현재" }, { value: "all", label: "정정·해지 포함" }]} />} pad={false}>
        {contracts.length === 0 ? <Empty>수주 공시가 없거나 아직 구조화 전입니다 (filingParseJob).</Empty> : (
          <div className="table-wrap max-h-[60vh]">
            <table className="data-table">
              <thead><tr><th>접수</th><th>계약</th><th className="num">금액</th><th className="num">매출 대비</th><th>지역</th>
                <th className="num">천 가구당 미분양</th><th>종료</th><th>상태</th></tr></thead>
              <tbody>{contracts.map((c) => {
                const st = state(c);
                const hot = (c.unsoldPer1kHh ?? 0) >= riskTh;
                return (
                  <tr key={c.rceptNo}>
                    <td className="whitespace-nowrap tabular"><DartLink rceptNo={c.rceptNo}>{c.rceptDt}</DartLink></td>
                    <td>{c.name}<span className="sub">{c.counterparty}</span></td>
                    <td className="num whitespace-nowrap">{eok(c.amount)}</td>
                    <td className="num">{c.pctOfRevenue != null ? `${num(c.pctOfRevenue, 2)}%` : "–"}</td>
                    <td>{c.regionCd ? <Link className="hover:underline" href={`/regions/${c.regionCd}`}>{c.regionName}</Link>
                      : <span className="text-muted">{MATCH[c.regionMatch] ?? c.regionMatch}</span>}
                      <span className="sub" title={c.regionText}>{c.regionText}</span></td>
                    <td className={`num ${hot ? "text-crit font-medium" : ""}`}>{c.unsoldPer1kHh != null ? <>{hot && "▲ "}{num(c.unsoldPer1kHh, 2)}</> : "–"}</td>
                    <td className="whitespace-nowrap tabular text-ink2">{c.endDate ?? "–"}</td>
                    <td><span className={`text-[11px] px-1.5 py-0.5 rounded border whitespace-nowrap ${st.cls}`}>{st.label}</span></td>
                  </tr>
                );
              })}</tbody>
            </table>
          </div>
        )}
      </Card>

      <Card title="채무보증 (타인에 대한 채무보증 결정)" sub="보증금액 · 자기자본 대비 · 회사 전체 보증 잔액 · PF 유형 보증 (정정 공시는 최신만)" pad={false}>
        {g.length === 0 ? <Empty>채무보증 공시가 없습니다.</Empty> : (
          <div className="table-wrap max-h-[60vh]">
            <table className="data-table">
              <thead><tr><th>접수</th><th>채무자 · 채권자</th><th className="num">보증금액</th><th className="num">자기자본 대비</th>
                <th className="num">총 잔액</th><th className="num">잔액/자기자본</th><th>PF 유형</th><th>종료</th></tr></thead>
              <tbody>{g.map((x) => (
                <tr key={x.rceptNo}>
                  <td className="whitespace-nowrap tabular"><DartLink rceptNo={x.rceptNo}>{x.rceptDt}</DartLink>{x.correction && <span className="sub">정정</span>}</td>
                  <td>{x.debtor ?? "–"}<span className="sub">{x.creditor}</span></td>
                  <td className="num whitespace-nowrap">{eok(x.amount)}</td>
                  <td className="num">{x.pctOfEquity != null ? `${num(x.pctOfEquity, 2)}%` : "–"}</td>
                  <td className="num whitespace-nowrap">{eok(x.totalBalance)}
                    {x.balanceIsLimit && <span className="sub" title="원문 주석: 총 잔액은 보증 한도이며 미사용분 포함">한도 기준{x.usedBalance != null ? ` · 사용 ${eok(x.usedBalance)}` : ""}</span>}</td>
                  <td className={`num ${(x.balanceToEquityPct ?? 0) >= 100 ? "text-crit font-medium" : ""}`}>{x.balanceToEquityPct != null ? `${num(x.balanceToEquityPct)}%` : "–"}</td>
                  <td>{x.pfLines.length === 0 ? <span className="text-muted">–</span> : x.pfLines.map((l, i) => (
                    <div key={i} className="text-xs whitespace-nowrap">{l.pfType} {eok(l.amount)}</div>))}</td>
                  <td className="whitespace-nowrap tabular text-ink2">{x.endDate ?? "–"}</td>
                </tr>))}</tbody>
            </table>
          </div>
        )}
      </Card>
      <p className="text-[11px] text-muted">원문(HTML)을 그대로 보관하고 파서 버전을 기록합니다 — 파서를 고치면 API 호출 없이 다시 구조화합니다.</p>
    </div>
  );
}
