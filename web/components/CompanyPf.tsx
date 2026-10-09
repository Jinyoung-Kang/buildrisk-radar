import Link from "next/link";
import { Card, DartLink, DownloadLink, Empty, ErrorBox, Loading, Prose } from "@/components/ui";
import { TrendLine } from "@/components/LazyCharts";
import { type PfReport, type PfScope, type PfStatus } from "@/lib/api";
import { eok, num, pk } from "@/lib/domain/format";
import { useApi } from "@/lib/hooks/useApi";
import { due12, PF_BUCKETS, PF_DUE12_TH, PF_GUARANTEE_TH } from "@/lib/domain/filings";
import * as companyApi from "@/lib/api/company";

const SCOPE: Record<PfScope, string> = { SOLE: "단독사업", CONSORTIUM_ALL: "컨소시엄 전체 (참고)", CONSORTIUM_SHARE: "컨소시엄 회사분" };
const STATUS: Record<PfStatus, string> = {
  PARSED: "구조화", NO_STANDARD: "요약표 없음", NONE: "PF 없음", NO_SECTION: "구간 없음", NO_DOC: "원문 없음",
};
const MATCH: Record<string, string> = { SIDO: "시도만", OVERSEAS: "해외", UNKNOWN: "주소 해석 불가", NONE: "지역 없음" };

/** 검증 한 줄 — 색만으로 구분하지 않도록 기호 + 글자 */
function Check({ ok, label, detail }: { ok?: boolean | null; label: string; detail: string }) {
  const [icon, cls, word] = ok == null ? ["–", "text-muted", "판단 불가"] : ok ? ["✓", "text-good", "일치"] : ["!", "text-warn", "다름"];
  return (
    <div className="text-xs min-w-0">
      <div className="flex items-baseline gap-1.5 whitespace-nowrap">
        <span aria-hidden className={`font-semibold w-3 text-center ${cls}`}>{icon}</span>
        <span className="text-ink2">{label}</span>
        <span className={`font-medium ${cls}`}>{word}</span>
      </div>
      <div className="text-[11px] text-muted pl-[1.125rem] truncate" title={detail}>{detail}</div>
    </div>
  );
}

function Pct({ v, th }: { v?: number; th: number }) {
  if (v == null) return <span className="text-muted">–</span>;
  const hot = v >= th;
  return <span className={hot ? "text-crit font-medium" : ""}>{hot && "▲ "}{num(v, 1, true)}%</span>;
}

/** 만기 구간 — 막대 길이는 대출잔액 대비, 12개월 안 구간은 진하게 + '12개월 안' 표시 */
function Maturity({ r }: { r: PfReport }) {
  const d = r.due;
  if (!d) return <Empty>{r.message ?? "만기 구간이 없습니다."}</Empty>;
  const vals = PF_BUCKETS.map((b) => d[b.key] ?? 0);
  const max = Math.max(...vals, 1);
  return (
    <div className="space-y-1.5" role="list" aria-label="대출잔액 만기 구간">
      {PF_BUCKETS.map((b, i) => (
        <div key={b.key} role="listitem" className="grid grid-cols-[5.5rem_1fr_5rem] items-center gap-2 text-xs">
          <span className={b.within ? "text-ink font-medium" : "text-ink2"}>{b.label}</span>
          <div className="h-2.5 bg-line/60 rounded-full overflow-hidden" aria-hidden>
            <div className={`h-full rounded-full ${b.within ? "bg-accent" : "bg-ink2/40"}`} style={{ width: `${(vals[i] / max) * 100}%` }} />
          </div>
          <span className="tabular text-right">{d[b.key] == null ? "–" : eok(d[b.key])}</span>
        </div>
      ))}
      <p className="text-[11px] text-muted pt-1">진한 막대 = 12개월 안에 만기가 돌아오는 대출잔액 (회사 노출 기준, 단독 + 컨소시엄 회사분)</p>
    </div>
  );
}

/** 정기보고서 부동산 PF 우발채무 (ADR-024) — 수주·보증 탭 위쪽 */
export default function CompanyPf({ corp, riskTh = 5 }: { corp: string; riskTh?: number }) {
  const { data, error } = useApi(companyApi.pf(corp));
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  const cur = data.current;
  const latest = data.history[0];
  const trend = [...data.history].reverse().filter((h) => h.status === "PARSED")
    .map((h) => ({ x: pk(h.periodKey), g: h.guaranteeToEquityPct ?? null, d: h.due12mToEquityPct ?? null }));
  const d12 = due12(cur?.due);

  if (!data.history.length) {
    return <Card title="부동산 PF 우발채무 (정기보고서)"><Empty>아직 수집한 정기보고서가 없습니다 (pfExposureJob).</Empty></Card>;
  }
  return (
    <div className="space-y-5">
      {!cur ? (
        <Card title="부동산 PF 우발채무 (정기보고서)" sub={`${latest.reportNm} · ${STATUS[latest.status]}`}>
          <p className="text-sm text-ink2"><Prose text={latest.message ?? "구조화한 PF 표가 없습니다."} /></p>
          {latest.status === "NO_STANDARD" && (
            <p className="text-xs text-muted mt-2"><Prose text="금융감독원은 2024년 1월 「건설회사 우발부채 주석공시 모범사례」(부동산 PF 종합요약표 · 사업장별 내역)를 내고 2023년도 재무제표부터 적용하도록 권고했습니다. 의무가 아니라 회사마다 도입 시점과 모양이 다르고, 이 표가 없는 보고서(예: 워크아웃 기간의 태영건설)는 숫자를 추정해 채우지 않습니다." /></p>
          )}
        </Card>
      ) : (
        <>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">PF 보증 (회사 노출)</div>
              <div className="text-lg font-semibold tabular">{eok(cur.guarantee)}</div>
              <div className="text-[11px] text-muted">{pk(cur.periodKey)} · 단독 + 컨소시엄 회사분{cur.bridgeSharePct != null && ` · 브릿지론 ${num(cur.bridgeSharePct, 1, true)}%`}</div></div>
            <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">PF 보증 / 자기자본</div>
              <div className="text-lg font-semibold tabular"><Pct v={cur.guaranteeToEquityPct} th={PF_GUARANTEE_TH} /></div>
              <div className="text-[11px] text-muted">자본총계 {eok(cur.equity)}{cur.equityFsDiv && ` (${cur.equityFsDiv === "CFS" ? "연결" : "별도"})`}</div></div>
            <div className="bg-raised rounded-xl shadow-card px-4 py-3"><div className="text-xs text-ink2">12개월 내 만기 PF 대출</div>
              <div className="text-lg font-semibold tabular">{eok(d12)}</div>
              <div className="text-[11px] text-muted">자기자본의 <Pct v={cur.due12mToEquityPct} th={PF_DUE12_TH} /></div></div>
            <div className="bg-raised rounded-xl shadow-card px-4 py-3 min-w-0 space-y-1"><div className="text-xs text-ink2">대조</div>
              <Check ok={cur.reconciled} label="사업장 합" detail={cur.sitesGuarantee != null ? eok(cur.sitesGuarantee) : "사업장 내역 없음"} />
              <Check ok={cur.statedMatch} label="본문 서술" detail={cur.statedTotal != null ? eok(cur.statedTotal) : "본문에 합계 없음"} />
            </div>
          </div>

          <div className="grid lg:grid-cols-2 gap-5">
            <Card title="분기별 PF 보증 · 12개월 내 만기 / 자기자본" sub={`빨간 점선 = R-C06 기본 임계 ${PF_GUARANTEE_TH}% (12개월 내 만기는 ${PF_DUE12_TH}%)`}>
              {trend.length < 2 ? <Empty>구조화한 분기가 {trend.length}개라 추이를 그리지 않습니다.</Empty> : (
                <TrendLine data={trend} series={[{ key: "g", name: "PF 보증 / 자기자본" }, { key: "d", name: "12개월 내 만기 / 자기자본" }]}
                  fmt={(v) => `${num(v, 0)}%`} tipFmt={(v) => `${num(v, 1, true)}%`} threshold={PF_GUARANTEE_TH} thresholdLabel={`${PF_GUARANTEE_TH}%`} />
              )}
            </Card>
            <Card title={`만기 구간 — ${pk(cur.periodKey)} 대출잔액 ${eok(cur.balance)}`} sub="요약표 합계 줄 기준">
              <Maturity r={cur} />
            </Card>
          </div>

          <Card title="종합요약표" sub={<>{cur.reportNm} · <DartLink rceptNo={cur.rceptNo}>원문</DartLink>{cur.message && ` · ${cur.message}`}</>} pad={false}>
            <div className="table-wrap">
              <table className="data-table">
                <thead><tr><th>범위</th><th>구분</th><th>종류</th><th className="num">보증한도</th><th className="num">보증금액</th><th className="num">대출잔액</th><th className="num">12개월 내 만기</th></tr></thead>
                <tbody>{data.summary.map((s, i) => (
                  <tr key={i} className={`${s.total ? "font-medium" : ""} ${s.scope === "CONSORTIUM_ALL" ? "text-muted" : ""}`}>
                    <td className="whitespace-nowrap">{SCOPE[s.scope]}</td>
                    <td>{s.total ? "합계" : s.category ?? "–"}</td><td>{s.total ? "" : s.pfType ?? "–"}</td>
                    <td className="num whitespace-nowrap">{eok(s.limit)}</td><td className="num whitespace-nowrap">{eok(s.guarantee)}</td>
                    <td className="num whitespace-nowrap">{eok(s.balance)}</td><td className="num whitespace-nowrap">{eok(due12(s.due))}</td>
                  </tr>))}</tbody>
              </table>
            </div>
            <p className="text-[11px] text-muted px-4 py-2">컨소시엄 전체(회색)는 다른 참여사 몫이 섞여 회사 노출 합계에 넣지 않습니다.</p>
          </Card>

          <Card title={`사업장별 PF 보증 ${data.sites.length}곳`} sub={`사업지역을 시군구로 해석해 그 지역의 최신 천 가구당 미분양과 잇습니다 (▲ = ${riskTh}호 이상)`} pad={false}
            right={data.sites.length > 0 && <DownloadLink href={companyApi.pfSitesCsv(corp)}>CSV</DownloadLink>}>
            {data.sites.length === 0 ? <Empty>사업장별 내역이 없습니다.</Empty> : (
              <div className="table-wrap max-h-[60vh]">
                <table className="data-table">
                  <thead><tr><th>사업지역</th><th>사업장 · PF</th><th>신용보강</th><th className="num">보증금액</th><th className="num">부담률</th>
                    <th className="num">대출잔액</th><th>만기</th><th>채무자</th><th className="num">천 가구당 미분양</th></tr></thead>
                  <tbody>{data.sites.map((s) => {
                    const hot = (s.unsoldPer1kHh ?? 0) >= riskTh;
                    return (
                      <tr key={s.seq}>
                        <td>{s.regionCd ? <Link className="hover:underline" href={`/regions/${s.regionCd}`}>{s.regionName}</Link>
                          : <span className="text-muted">{s.sidoName ?? MATCH[s.regionMatch] ?? s.regionMatch}</span>}
                          <span className="sub" title={s.regionText}>{s.regionText}</span></td>
                        <td>{s.siteType ?? "–"}<span className="sub">{[s.category, s.pfType].filter(Boolean).join(" · ")}</span></td>
                        <td className="text-xs">{s.enhancement ?? "–"}</td>
                        <td className="num whitespace-nowrap">{eok(s.guarantee)}</td>
                        <td className="num">{s.sharePct != null ? `${num(s.sharePct, 1)}%` : "–"}</td>
                        <td className="num whitespace-nowrap">{eok(s.balance)}</td>
                        <td className="whitespace-nowrap tabular text-ink2 text-xs">{s.maturity ?? "–"}</td>
                        <td className="text-xs">{s.debtor ?? "–"}{s.related && <span className="sub">특수관계자</span>}</td>
                        <td className={`num ${hot ? "text-crit font-medium" : ""}`}>{s.unsoldPer1kHh != null ? <>{hot && "▲ "}{num(s.unsoldPer1kHh, 2)}</> : "–"}</td>
                      </tr>
                    );
                  })}</tbody>
                </table>
              </div>
            )}
          </Card>
        </>
      )}

      <Card title="분기별 정기보고서" sub={`구조화 ${data.coverage.parsed} · 요약표 없음 ${data.coverage.noStandard} · PF 없음 ${data.coverage.none} · 구간 없음 ${data.coverage.noSection + data.coverage.noDoc}`} pad={false}>
        <div className="table-wrap max-h-[40vh]">
          <table className="data-table">
            <thead><tr><th>분기</th><th>보고서</th><th>상태</th><th className="num">PF 보증</th><th className="num">/ 자기자본</th><th className="num">12개월 내 만기 / 자기자본</th><th>대조</th></tr></thead>
            <tbody>{data.history.map((h) => (
              <tr key={h.rceptNo}>
                <td className="tabular whitespace-nowrap">{pk(h.periodKey)}</td>
                <td><DartLink rceptNo={h.rceptNo}>{h.rceptDt}</DartLink><span className="sub">{h.reportNm}</span></td>
                <td className="text-xs whitespace-nowrap" title={h.message}>{STATUS[h.status]}</td>
                <td className="num whitespace-nowrap">{eok(h.guarantee)}</td>
                <td className="num"><Pct v={h.guaranteeToEquityPct} th={PF_GUARANTEE_TH} /></td>
                <td className="num"><Pct v={h.due12mToEquityPct} th={PF_DUE12_TH} /></td>
                <td className="text-xs whitespace-nowrap">{h.status !== "PARSED" ? "–" : (
                  <>사업장 {h.reconciled == null ? "–" : h.reconciled ? "✓" : "!"} · 본문 {h.statedMatch == null ? "–" : h.statedMatch ? "✓" : "!"}</>)}</td>
              </tr>))}</tbody>
          </table>
        </div>
      </Card>
      <details className="text-[11px] text-muted">
        <summary className="cursor-pointer">계산 방법</summary>
        <ul className="list-disc pl-5 mt-1 space-y-0.5">{data.method.map((m) => <li key={m}><Prose text={m} /></li>)}</ul>
      </details>
    </div>
  );
}
