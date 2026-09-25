import Link from "next/link";
import { useMemo, useState } from "react";
import Layout from "@/components/Layout";
import { Card, Empty, ErrorBox, Loading, PageTitle, Segmented } from "@/components/ui";
import { qs, type Exposure, type ExposureRow } from "@/lib/api";
import { eok, num } from "@/lib/format";
import { useApi } from "@/lib/useApi";

type SortKey = "risk" | "guarantee" | "amount" | "name";

/** 막대 한 칸 (0~100%) — 숫자와 함께 표시해 색에만 의존하지 않음 */
function ShareBar({ pct, warn }: { pct?: number; warn: number }) {
  if (pct == null) return <span className="text-muted">–</span>;
  return (
    <div className="flex items-center gap-2 justify-end">
      <div className="w-24 h-2 bg-line rounded-full overflow-hidden" aria-hidden>
        <div className={`h-full ${pct >= warn ? "bg-crit" : "bg-accent"}`} style={{ width: `${Math.min(100, pct)}%` }} />
      </div>
      <span className={`tabular w-12 text-right ${pct >= warn ? "text-crit font-medium" : ""}`}>{num(pct)}%</span>
    </div>
  );
}

export default function ExposurePage() {
  const [days, setDays] = useState<"180" | "365" | "730">("365");
  const [th, setTh] = useState<"3" | "5" | "10">("5");
  const [sort, setSort] = useState<SortKey>("risk");
  const { data, error } = useApi<Exposure>(`/exposure${qs({ days, unsoldPer1kHh: th })}`);
  const rows = useMemo(() => {
    const r = [...(data?.items ?? [])];
    const by: Record<SortKey, (a: ExposureRow, b: ExposureRow) => number> = {
      risk: (a, b) => (b.riskSharePct ?? -1) - (a.riskSharePct ?? -1),
      guarantee: (a, b) => (b.latestBalanceToEquityPct ?? -1) - (a.latestBalanceToEquityPct ?? -1),
      amount: (a, b) => (b.totalAmount ?? 0) - (a.totalAmount ?? 0),
      name: (a, b) => a.corpName.localeCompare(b.corpName, "ko"),
    };
    return r.sort(by[sort]);
  }, [data, sort]);
  const withContracts = rows.filter((r) => r.contracts > 0).length;
  return (
    <Layout title="수주·보증 노출">
      <PageTitle title="수주·보증 노출 — 기업 × 지역"
        sub="건설사가 공시한 공급계약 원문에서 공사 지역과 금액을 뽑아, 미분양이 많은 지역에 앞으로의 매출이 얼마나 걸려 있는지 봅니다. 채무보증 공시로 우발채무(PF 보증 포함)도 함께 봅니다." />
      <div className="flex flex-wrap gap-3 mb-4 items-center">
        <Segmented label="기간" value={days} onChange={setDays} options={[{ value: "180", label: "6개월" }, { value: "365", label: "1년" }, { value: "730", label: "2년" }]} />
        <Segmented label="위험 지역 기준 (천 가구당 미분양)" value={th} onChange={setTh} options={[{ value: "3", label: "3호↑" }, { value: "5", label: "5호↑" }, { value: "10", label: "10호↑" }]} />
        <Segmented label="정렬" value={sort} onChange={setSort} options={[{ value: "risk", label: "위험 지역 비중" }, { value: "guarantee", label: "보증/자기자본" }, { value: "amount", label: "수주 금액" }, { value: "name", label: "이름" }]} />
      </div>
      <ErrorBox error={error} />
      {!data ? <Loading /> : (
        <Card pad={false} title={`유니버스 ${rows.length}개사 · 수주 공시 있음 ${withContracts}개사`} sub={data.method}>
          {rows.length === 0 ? <Empty>기업이 없습니다.</Empty> : (
            <div className="table-wrap max-h-[70vh]">
              <table className="data-table">
                <thead><tr><th>기업</th><th className="num">현재 수주</th><th className="num">시군구 매핑</th>
                  <th className="num">위험 지역 비중</th><th className="num">위험 계약</th><th className="num">보증 잔액/자기자본</th>
                  <th className="num">PF 보증 (기간 합)</th><th className="num">열린 경보</th></tr></thead>
                <tbody>{rows.map((r) => (
                  <tr key={r.corpCode}>
                    <td><Link href={`/companies/${r.corpCode}?tab=filings`} className="font-medium hover:underline">{r.corpName}</Link></td>
                    <td className="num whitespace-nowrap">{r.contracts ? <>{r.contracts}건<span className="sub">{eok(r.totalAmount)}</span></> : <span className="text-muted">–</span>}</td>
                    <td className="num whitespace-nowrap">{eok(r.mappedAmount)}</td>
                    <td className="num"><ShareBar pct={r.riskSharePct} warn={50} /></td>
                    <td className="num">{r.riskContracts || "–"}</td>
                    <td className={`num ${(r.latestBalanceToEquityPct ?? 0) >= 100 ? "text-crit font-medium" : ""}`}>
                      {r.latestBalanceToEquityPct != null ? `${num(r.latestBalanceToEquityPct)}%` : "–"}
                      {r.latestGuaranteeDt && <span className="sub">{r.latestGuaranteeDt}</span>}</td>
                    <td className="num whitespace-nowrap">{eok(r.pfAmount)}</td>
                    <td className="num">{r.openAlerts || "–"}</td>
                  </tr>))}</tbody>
              </table>
            </div>
          )}
        </Card>
      )}
      <p className="text-[11px] text-muted mt-3">빨강 = 위험 지역 비중 50% 이상(R-X01 기본값) · 보증 잔액이 자기자본 이상(R-C05 기본값). 해외·주소 해석 불가 계약은 비중 계산에서 빠집니다. {data?.disclaimer}</p>
    </Layout>
  );
}
