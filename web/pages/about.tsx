import Layout from "@/components/Layout";
import { Card, Loading, PageTitle } from "@/components/ui";
import type { Meta } from "@/lib/api";
import { useApi } from "@/lib/useApi";

const RISK_EVENTS = new Set(["REHAB", "DEFAULT", "SUSPENSION", "AUDIT_OPINION"]);

export default function About() {
  const { data } = useApi<Meta>("/meta");
  const company = data?.metrics.filter((m) => m.target === "COMPANY") ?? [];
  const region = data?.metrics.filter((m) => m.target === "REGION") ?? [];
  const metricTable = (rows: Meta["metrics"]) => (
    <div className="table-wrap">
      <table className="data-table">
        <thead><tr><th>지표</th><th>계산식</th><th>단위</th><th>위험 방향</th><th>출처</th></tr></thead>
        <tbody>{rows.map((m) => (
          <tr key={m.code}>
            <td className="whitespace-nowrap"><b>{m.nameKo}</b><span className="sub tabular">{m.code}</span></td>
            <td className="text-ink2">{m.formula}</td>
            <td className="whitespace-nowrap">{m.unit}</td>
            <td className="whitespace-nowrap">{m.higherIsRisk ? "클수록 위험 ▲" : "작을수록 위험 ▼"}</td>
            <td className="whitespace-nowrap text-ink2">{m.source}</td>
          </tr>))}</tbody>
      </table>
    </div>
  );
  return (
    <Layout title="지표·출처">
      <PageTitle title="지표 정의 · 데이터 출처" sub="모든 지표·경보는 원천(접수번호·통계표·수집 run)까지 거슬러 올라갈 수 있습니다." />
      {!data ? <Loading /> : (
        <div className="space-y-5">
          <div className="rounded-lg border border-serious/50 bg-serious/5 px-4 py-3 text-sm">⚠ {data.disclaimer} 임계값은 업계 공식 기준이 아니라 탐색용 기본값입니다.</div>
          <Card title={`기업 지표 (${company.length})`} sub="분모가 0·음수(자본잠식)이거나 계정이 없으면 값 대신 상태 코드(NEG_EQUITY · ZERO_DENOM · MISSING)를 저장합니다. 음수일 수 없는 분모(이자비용 등)의 분기값이 음수면 — 보고서마다 원천 계정이 달라 누적이 줄어든 경우 — 부호가 뒤집힌 비율 대신 INCONSISTENT. 손익·현금흐름은 당기누적금액 기준, 분기값은 누적 차분(Q2 = 반기누적 − Q1).">
            {metricTable(company)}
          </Card>
          <Card title={`지역 지표 (${region.length})`} sub="일반구가 있는 시는 시 단위로 모읍니다 (미분양·가구는 합계, 가격지수는 평균).">
            {metricTable(region)}
          </Card>
          <Card title="공시 이벤트 분류 사전" sub="보고서명 머리말([기재정정] 등)·공백을 지운 뒤 위에서부터 첫 키워드로 분류합니다. 빨간 유형은 규칙 R-C04 경보 대상입니다.">
            <div className="table-wrap">
              <table className="data-table">
                <thead><tr><th>순서</th><th>유형</th><th>키워드</th></tr></thead>
                <tbody>{data.eventTypes.map((e, i) => (
                  <tr key={e.code}>
                    <td className="num text-muted w-12">{i + 1}</td>
                    <td className="whitespace-nowrap"><b className={RISK_EVENTS.has(e.code) ? "text-crit" : ""}>{e.name}</b><span className="sub tabular">{e.code}</span></td>
                    <td><div className="flex flex-wrap gap-1.5">{e.keywords.map((k) => (
                      <span key={k} className="text-xs border border-line rounded px-1.5 py-0.5 bg-page">{k}</span>))}</div></td>
                  </tr>))}</tbody>
              </table>
            </div>
          </Card>
          <Card title="데이터 출처">
            <div className="table-wrap">
              <table className="data-table">
                <thead><tr><th>제공처</th><th>이 서비스에서 쓰는 데이터</th><th>주소</th></tr></thead>
                <tbody>{data.sources.map((s) => (
                  <tr key={s.code}>
                    <td className="whitespace-nowrap font-medium">{s.name}</td><td className="text-ink2">{s.use}</td>
                    <td className="whitespace-nowrap"><a className="text-accent hover:underline" href={s.url} target="_blank" rel="noreferrer">{s.url.replace(/^https?:\/\//, "")}</a></td>
                  </tr>))}</tbody>
              </table>
            </div>
            <p className="text-xs text-muted mt-3">API 문서: <a className="underline" href="/swagger-ui/index.html">Swagger UI</a></p>
          </Card>
        </div>
      )}
    </Layout>
  );
}
