import Link from "next/link";
import { useMemo, useState } from "react";
import Layout from "@/components/Layout";
import { Card, DownloadLink, Empty, ErrorBox, Loading, PageTitle, Prose, Segmented } from "@/components/ui";
import { sortExposure, type ExposureSort } from "@/lib/domain/exposure";
import { STATUS_LABEL, eok, int, num, pk } from "@/lib/domain/format";
import { useApi } from "@/lib/hooks/useApi";
import * as insightApi from "@/lib/api/insight";

/** 막대 한 칸 (0~100%) — 숫자와 함께 표시해 색에만 의존하지 않음 */
function ShareBar({ pct, warn }: { pct?: number; warn: number }) {
  if (pct == null) return <span className="text-muted">–</span>;
  return (
    <div className="flex items-center gap-2 justify-end">
      <div className="w-24 h-2 bg-line rounded-full overflow-hidden" aria-hidden>
        <div className={`h-full ${pct >= warn ? "bg-crit" : "bg-accent"}`} style={{ width: `${Math.min(100, pct)}%` }} />
      </div>
      <span className={`tabular w-12 text-right ${pct >= warn ? "text-crit font-medium" : ""}`}>{num(pct, 1, true)}%</span>
    </div>
  );
}

type Days = "180" | "365" | "730";
type Th = "3" | "5" | "10";

export default function ExposurePage() {
  // 고르기 전에는 비워 두고 서버 기본값(경보 R-X01 의 최신 파라미터)을 받아 그 기준으로 표시 — 365 · 5 를 늘 붙여 보내
  // 규칙을 바꾸면 경보 · 기업 카드와 숫자가 다시 갈라졌음 (VERIFICATION 208)
  const [days, setDays] = useState<Days | null>(null);
  const [th, setTh] = useState<Th | null>(null);
  const [sort, setSort] = useState<ExposureSort>("risk");
  const { data, error } = useApi(insightApi.exposure(days ?? undefined, th ?? undefined));
  const shownDays = (days ?? (data ? String(data.days) : "")) as Days;
  const shownTh = (th ?? (data ? String(data.unsoldPer1kHh) : "")) as Th;
  const rows = useMemo(() => sortExposure(data?.items ?? [], sort), [data, sort]);
  const withContracts = rows.filter((r) => r.contracts > 0).length;
  return (
    <Layout title="수주·보증 노출">
      <PageTitle title="수주·보증 노출 — 기업 × 지역"
        sub="건설사가 공시한 공급계약 원문에서 공사 지역과 금액을 뽑아, 미분양이 많은 지역에 앞으로의 매출이 얼마나 걸려 있는지 봅니다. 채무보증 공시와 정기보고서의 부동산 PF 종합요약표로 우발채무도 함께 봅니다." />
      <div className="flex flex-wrap gap-3 mb-4 items-center">
        <Segmented label="기간" value={shownDays} onChange={setDays} options={[{ value: "180", label: "6개월" }, { value: "365", label: "1년" }, { value: "730", label: "2년" }]} />
        <Segmented label="위험 지역 기준 (천 가구당 미분양)" value={shownTh} onChange={setTh} options={[{ value: "3", label: "3호↑" }, { value: "5", label: "5호↑" }, { value: "10", label: "10호↑" }]} />
        <Segmented label="정렬" value={sort} onChange={setSort} options={[{ value: "risk", label: "위험 지역 비중" }, { value: "presale", label: "분양 위험 비중" }, { value: "guarantee", label: "보증/자기자본" }, { value: "pf", label: "PF/자기자본" }, { value: "amount", label: "수주 금액" }, { value: "name", label: "이름" }]} />
        <DownloadLink href={insightApi.exposureCsv(days ?? (data ? String(data.days) : undefined), th ?? (data ? String(data.unsoldPer1kHh) : undefined))}>CSV</DownloadLink>
      </div>
      <ErrorBox error={error} />
      {/* 값이 없을 때 실패하면 오류만 — 전에는 오류와 함께 '불러오는 중'이 끝나지 않았음 (VERIFICATION 163 · 167) */}
      {!data ? (error ? null : <Loading />) : (
        <Card pad={false} title={`유니버스 ${rows.length}개사 · 수주 공시 있음 ${withContracts}개사`} sub={data.method}>
          {rows.length === 0 ? <Empty>기업이 없습니다.</Empty> : (
            <div className="table-wrap max-h-[70vh]">
              <table className="data-table">
                <thead><tr><th>기업</th><th className="num">현재 수주</th><th className="num">시군구 매핑</th>
                  <th className="num">위험 지역 비중</th><th className="num">위험 계약</th><th className="num">보증 잔액/자기자본</th>
                  <th className="num">PF 보증 / 자기자본<span className="sub">정기보고서</span></th><th className="num">12개월 내 만기 / 자기자본</th>
                  <th className="num">PF 보증 공시 (기간 합)</th><th className="num">분양 공고<span className="sub">청약홈 · 세대</span></th>
                  <th className="num">분양 위험 지역 비중</th><th className="num">열린 경보</th></tr></thead>
                <tbody>{rows.map((r) => (
                  <tr key={r.corpCode}>
                    <td><Link href={`/companies/${r.corpCode}?tab=filings`} className="font-medium hover:underline">{r.corpName}</Link></td>
                    <td className="num whitespace-nowrap">{r.contracts ? <>{r.contracts}건<span className="sub">{eok(r.totalAmount)}</span></> : <span className="text-muted">–</span>}</td>
                    <td className="num whitespace-nowrap">{eok(r.mappedAmount)}</td>
                    <td className="num"><ShareBar pct={r.riskSharePct} warn={50} /></td>
                    <td className="num">{r.riskContracts || "–"}</td>
                    <td className={`num ${(r.latestBalanceToEquityPct ?? 0) >= 100 ? "text-crit font-medium" : ""}`}>
                      {r.latestBalanceToEquityPct != null ? `${num(r.latestBalanceToEquityPct, 1, true)}%` : "–"}
                      {r.latestGuaranteeDt && <span className="sub">{r.latestGuaranteeDt}</span>}</td>
                    <td className={`num ${(r.pfGuaranteeToEquityPct ?? 0) >= 100 ? "text-crit font-medium" : ""}`}>
                      {r.pfGuaranteeToEquityPct != null ? `${num(r.pfGuaranteeToEquityPct, 1, true)}%` : r.pfStatus === "NEG_EQUITY" ? STATUS_LABEL.NEG_EQUITY : "–"}
                      {r.pfPeriod && <span className="sub">{pk(r.pfPeriod)}</span>}</td>
                    <td className={`num ${(r.pfDue12mToEquityPct ?? 0) >= 50 ? "text-crit font-medium" : ""}`}>
                      {r.pfDue12mToEquityPct != null ? `${num(r.pfDue12mToEquityPct, 1, true)}%` : r.pfStatus === "NEG_EQUITY" ? STATUS_LABEL.NEG_EQUITY : "–"}</td>
                    <td className="num whitespace-nowrap">{eok(r.pfAmount)}</td>
                    <td className="num whitespace-nowrap">{r.presaleNotices ? <Link href={`/companies/${r.corpCode}?tab=presales`} className="hover:underline">
                      {r.presaleNotices}건<span className="sub">{int(r.presaleUnits)}세대</span></Link> : <span className="text-muted">–</span>}</td>
                    <td className="num"><ShareBar pct={r.presaleRiskSharePct} warn={50} /></td>
                    <td className="num">{r.openAlerts || "–"}</td>
                  </tr>))}</tbody>
              </table>
            </div>
          )}
        </Card>
      )}
      <p className="text-[11px] text-muted mt-3"><Prose text={`빨강 = 위험 지역 비중 50% 이상(R-X01 기본값) · 보증 잔액이 자기자본 이상(R-C05 기본값) · 정기보고서 PF 보증이 자기자본 이상 또는 12개월 내 만기가 자기자본의 50% 이상(R-C06 기본값). 해외·주소 해석 불가 계약은 비중 계산에서 빠집니다. 분양 = 청약홈 APT 분양 공고 중 시공사로 대조된 것(컨소시엄은 세대를 나누지 않음), 비중은 미분양 지표가 있는 시군구 공고 세대 기준(R-X02 와 같은 규칙). ${data?.disclaimer ?? ""}`} /></p>
    </Layout>
  );
}
