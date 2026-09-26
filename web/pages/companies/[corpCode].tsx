import Link from "next/link";
import { useRouter } from "next/router";
import { useMemo, useState } from "react";
import { SignedBars, TrendLine } from "@/components/Charts";
import EvidenceView from "@/components/EvidenceView";
import CompanyFilings from "@/components/CompanyFilings";
import Layout from "@/components/Layout";
import PriceChart from "@/components/PriceChart";
import Tabs, { TabPanel, useTab } from "@/components/Tabs";
import { Card, DartLink, Empty, ErrorBox, Loading, MetricStatus, PageTitle, Segmented, SeverityBadge, StatusPill } from "@/components/ui";
import { qs, type AlertDetail, type CompanySummary, type Disclosure, type Financials, type Metrics } from "@/lib/api";
import { eok, num, pk, withUnit } from "@/lib/format";
import { useApi } from "@/lib/useApi";

const EVENT_LABEL: Record<string, string> = {
  REHAB: "회생·구조조정", DEFAULT: "부도·채무불이행", SUSPENSION: "거래정지", AUDIT_OPINION: "감사의견",
  GUARANTEE: "채무보증", LITIGATION: "소송", FUNDING: "자금조달", CONTRACT: "수주·공급계약", PERIODIC: "정기공시", OTHER: "기타",
};
const RISK_EVENTS = new Set(["REHAB", "DEFAULT", "SUSPENSION", "AUDIT_OPINION"]);

function AlertCard({ id }: { id: number }) {
  const { data } = useApi<AlertDetail>(`/alerts/${encodeURIComponent(id)}`);
  if (!data) return <Loading />;
  return (
    <div className="border border-line rounded-lg p-3">
      <div className="flex items-center gap-2 mb-2 flex-wrap">
        <SeverityBadge s={data.severity} /><b className="text-sm">{data.title}</b>
        <span className="text-xs text-muted">{data.ruleCode} v{data.ruleVersion} · {data.asOf}</span>
        <StatusPill status={data.status} reason={data.closeReason} />
      </div>
      <EvidenceView ev={data.evidence} />
    </div>
  );
}

const TABS = ["overview", "financials", "filings", "price", "disclosures"] as const;
type Tab = (typeof TABS)[number];

export default function CompanyDetail() {
  const { query, isReady } = useRouter();
  const raw = isReady ? String(query.corpCode) : null;
  const corp = raw && /^\d{8}$/.test(raw) ? raw : null;          // 경로 파라미터는 형식이 맞을 때만 API 경로에 씀
  const [tab, setTab] = useTab<Tab>(TABS, "overview");
  const [fsDiv, setFsDiv] = useState<"AUTO" | "CFS" | "OFS">("AUTO");
  const [eventFilter, setEventFilter] = useState<"risk" | "all">("all");
  const summary = useApi<CompanySummary>(corp ? `/companies/${encodeURIComponent(corp)}` : null);
  // 탭에 필요한 데이터만 그 탭을 열 때 불러옴
  const metrics = useApi<Metrics>(corp && tab === "financials" ? `/companies/${encodeURIComponent(corp)}/metrics` : null);
  const fin = useApi<Financials>(corp && tab === "financials" ? `/companies/${encodeURIComponent(corp)}/financials${qs({ fsDiv })}` : null);
  const disc = useApi<Disclosure[]>(corp && tab === "disclosures" ? `/companies/${encodeURIComponent(corp)}/disclosures` : null);
  const s = summary.data;

  const series = useMemo(() => {
    const by: Record<string, { x: string; v?: number }[]> = {};
    metrics.data?.metrics.forEach((m) => { by[m.code] = m.points.map((p) => ({ x: pk(p.periodKey), v: p.value ?? undefined })); });
    return by;
  }, [metrics.data]);

  const periods = useMemo(() => Array.from(new Set(fin.data?.series.flatMap((x) => x.points.map((p) => p.periodKey)) ?? [])).sort().slice(-8), [fin.data]);
  const disclosures = (disc.data ?? []).filter((d) => eventFilter === "all" || RISK_EVENTS.has(d.eventType));

  return (
    <Layout title={s?.corpName ?? "기업"}>
      <ErrorBox error={summary.error} />
      {!s ? <Loading /> : (
        <>
          <PageTitle title={<>{s.corpName} <span className="text-sm font-normal text-muted">{s.stockCode} · {s.corpCode}</span></>}
            sub={<>{s.indutyCode} · {s.address ?? ""} {s.homepage && <a className="text-accent ml-1" href={s.homepage.startsWith("http") ? s.homepage : `http://${s.homepage}`} target="_blank" rel="noreferrer">홈페이지</a>}</>}
            right={<div className="text-xs text-muted text-right">
              기준 {s.latest ? `${pk(s.latest.periodKey)} · ${s.latest.fsDiv === "OFS" ? "별도" : "연결"}` : "–"} · 원천 <DartLink rceptNo={s.latest?.rceptNo} /><br />
              수집 보고서 {s.fetch.reportsOk}건 (없음 {s.fetch.reportsNoData}) · {s.fetch.lastFetchedAt ?? "미수집"}
            </div>} />

          <Tabs label="기업 정보" value={tab} onChange={setTab} tabs={[
            { id: "overview", label: "개요", badge: s.alerts.length }, { id: "financials", label: "재무·지표" },
            { id: "filings", label: "수주·보증" }, { id: "price", label: "주가·경보" }, { id: "disclosures", label: "공시" }]} />

          {tab === "overview" && <TabPanel id="overview">
          <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3 mb-5">
            {(s.latest?.metrics ?? []).map((m) => (
              <div key={m.code} className="bg-raised rounded-xl shadow-card px-3 py-2.5" title={m.formula}>
                <div className="text-xs text-ink2">{m.nameKo}</div>
                <div className="text-lg font-semibold tabular">{withUnit(m.value, m.unit)}<MetricStatus status={m.status} /></div>
                {m.code === "INTEREST_COVERAGE" && m.value != null && m.value < 0 && <div className="text-[11px] text-muted">영업손실 분기</div>}
                {m.yoyPp != null && <div className="text-[11px] text-muted">전년 동기 {m.yoyPp > 0 ? "+" : ""}{num(m.yoyPp)}%p</div>}
              </div>
            ))}
            {!s.latest && <div className="col-span-full"><Empty>지표가 아직 없습니다 — financialStatementJob → standardizeMetricJob 을 실행하세요.</Empty></div>}
          </div>

          {s.alerts.length > 0 && (
            <Card title={`열린 경보 ${s.alerts.length}건`} sub="규칙 조건 · 임계값 · 관측값 · 원천 접수번호" className="mb-5">
              <div className="space-y-3">{s.alerts.map((a) => <AlertCard key={a.alertId} id={a.alertId} />)}</div>
            </Card>
          )}
          {s.alerts.length === 0 && <Empty>열린 경보가 없습니다.</Empty>}
          </TabPanel>}

          {tab === "financials" && <TabPanel id="financials">
          <div className="grid lg:grid-cols-2 gap-5 mb-5">
            <Card title="부채비율 (%)" sub="부채총계 ÷ 자본총계 × 100 · R-C01 임계 300%">
              <TrendLine data={(series.DEBT_RATIO ?? []).map((d) => ({ x: d.x, v: d.v }))} series={[{ key: "v", name: "부채비율" }]}
                threshold={300} fmt={(v) => `${num(v, 0)}%`} />
            </Card>
            <Card title="이자보상배율 (배)" sub="분기 영업이익 ÷ 분기 이자비용 · R-C02 임계 1배 · 축은 -10~20배로 자름">
              <TrendLine data={(series.INTEREST_COVERAGE ?? []).map((d) => ({ x: d.x, v: d.v }))} series={[{ key: "v", name: "이자보상배율" }]}
                threshold={1} fmt={(v) => `${num(v, 1)}배`} clamp={[-10, 20]}
                tipFmt={(v) => (v < 0 ? `영업손실 (${num(v, 1)}배)` : `${num(v, 1)}배`)} />
            </Card>
            <Card title="분기 영업활동현금흐름" sub="누적 차분 · 빨강 = 유출(R-C03)">
              <SignedBars data={(series.OCF_QTR ?? []).map((d) => ({ x: d.x, v: d.v }))} dataKey="v" name="영업현금흐름" fmt={(v) => eok(v)} />
            </Card>
            <Card title="차입금의존도 (%)" sub="(단기 + 장기차입금 + 사채) ÷ 자산총계">
              <TrendLine data={(series.BORROWING_DEP ?? []).map((d) => ({ x: d.x, v: d.v }))} series={[{ key: "v", name: "차입금의존도" }]}
                fmt={(v) => `${num(v, 0)}%`} />
            </Card>
          </div>

          <Card title="표준계정 재무 (최근 8개 기간)" sub={fin.data?.basisNote} className="mb-5"
            right={<Segmented label="연결·별도" value={fsDiv} onChange={setFsDiv}
              options={[{ value: "AUTO", label: "자동(연결 우선)" }, { value: "CFS", label: "연결" }, { value: "OFS", label: "별도" }]} />}>
            {!fin.data?.series.some((x) => x.points.length) ? <Empty>이 구분의 재무 데이터가 없습니다.</Empty> : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm tabular">
                  <thead><tr className="text-xs text-ink2 border-b border-line">
                    <th className="text-left py-2 pr-3 font-medium">계정 (단위 억 원)</th>
                    {periods.map((p) => <th key={p} className="text-right px-2 font-medium">{pk(p)}</th>)}
                  </tr></thead>
                  <tbody>
                    {fin.data!.series.map((row) => {
                      const by = Object.fromEntries(row.points.map((p) => [p.periodKey, p]));
                      return (
                        <tr key={row.stdCode} className="border-b border-line/60">
                          <td className="py-1.5 pr-3">{row.nameKo}<span className="text-[11px] text-muted ml-1">{row.flowType === "FLOW" ? "누적" : ""}</span></td>
                          {periods.map((p) => {
                            const pt = by[p];
                            return (
                              <td key={p} className="text-right px-2" title={pt ? `${pt.sourceAccount ?? ""} · ${pt.fsDiv} · ${pt.rceptNo ?? ""}${pt.qtrAmount != null ? ` · 분기 ${eok(pt.qtrAmount)}` : ""}` : "없음"}>
                                {pt?.amount != null ? eok(pt.amount).replace("억", "") : <span className="text-muted">–</span>}
                              </td>
                            );
                          })}
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          </TabPanel>}

          {tab === "filings" && corp && <TabPanel id="filings"><CompanyFilings corp={corp} /></TabPanel>}

          {tab === "price" && corp && <TabPanel id="price">
            <Card title="주가와 경보" sub="일별 종가 3년 · 경보가 공개된 날 표시 — 규칙별 평균 효과는 '경보 검증' 화면">
              <PriceChart corp={corp} />
            </Card>
          </TabPanel>}

          {tab === "disclosures" && <TabPanel id="disclosures">
          <Card title="공시 타임라인 (최근 1년)" sub="보고서명 키워드 사전으로 분류 · 원문은 DART"
            right={<Segmented label="공시 필터" value={eventFilter} onChange={setEventFilter}
              options={[{ value: "all", label: "전체" }, { value: "risk", label: "중대 공시만" }]} />}>
            {disclosures.length === 0 ? <Empty>표시할 공시가 없습니다.</Empty> : (
              <ol className="relative border-l border-line ml-2">
                {disclosures.slice(0, 80).map((d) => (
                  <li key={d.rceptNo} className="ml-4 py-1.5">
                    <span className={`absolute -left-[5px] mt-1.5 w-2.5 h-2.5 rounded-full ${RISK_EVENTS.has(d.eventType) ? "bg-crit" : "bg-line"}`} aria-hidden />
                    <div className="text-sm flex flex-wrap gap-x-2 items-baseline">
                      <span className="text-xs text-muted tabular w-20">{d.rceptDt}</span>
                      <a className="hover:underline" href={d.url} target="_blank" rel="noreferrer">{d.reportNm}</a>
                      <span className={`text-[11px] px-1 rounded border ${RISK_EVENTS.has(d.eventType) ? "border-crit text-crit" : "border-line text-ink2"}`}>
                        {EVENT_LABEL[d.eventType] ?? d.eventType}</span>
                      <span className="text-[11px] text-muted">{d.flrNm}</span>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </Card>
          </TabPanel>}
          <p className="text-xs text-muted mt-4">{s.disclaimer} <Link href="/about" className="underline">지표 정의</Link></p>
        </>
      )}
    </Layout>
  );
}
