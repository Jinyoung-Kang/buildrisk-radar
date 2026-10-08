import { useEffect, useState } from "react";
import Layout from "@/components/Layout";
import { Card, PageTitle, Prose } from "@/components/ui";

/** 공개 읽기 API 안내 (ADR-027) — 화면이 쓰는 API 를 그대로 공개. 키 없이 GET, 변경은 로그인 · 관리자만 */
const GROUPS: { title: string; rows: [string, string][] }[] = [
  { title: "기업", rows: [
    ["GET /companies", "유니버스 목록 (q · sort · page · size)"],
    ["GET /companies/{corpCode}", "요약 — 최신 지표 · 열린 경보"],
    ["GET /companies/{corpCode}/financials", "표준계정 시계열 (fsDiv = AUTO · CFS · OFS, 누적 + 분기값)"],
    ["GET /companies/{corpCode}/metrics", "지표 시계열 — 값 · 상태 코드 · 분자/분모(components) · 원천 접수번호"],
    ["GET /companies/{corpCode}/filings · /pf · /sites · /prices", "수주 · 채무보증 · PF 우발채무 · 사업장 카드 · 일별 종가"],
  ] },
  { title: "지역", rows: [
    ["GET /regions", "시군구 목록 + 지표 값 (sido · metric · period)"],
    ["GET /regions/{regionCd}/series", "통계 · 지표 시계열과 출처 코드 매핑"],
    ["GET /regions/{regionCd}/contracts · /sites", "그 지역에 걸린 수주 · 사업장 카드"],
  ] },
  { title: "경보 · 근거", rows: [
    ["GET /alerts", "경보 목록 (targetType · severity · status · since · targetKey · ruleCode)"],
    ["GET /alerts/{alertId}", "경보 상세 + 근거(evidence)"],
    ["GET /alerts/{alertId}/lineage", "근거 계보 — 규칙 버전 → 지표 → 표준계정 → 원천 재무제표 줄(매핑 규칙) · 통계 · 공시 → 수집 · 계산 실행"],
    ["GET /rules", "규칙 · 파라미터 · 버전 이력"],
    ["GET /scorecard · /backtest", "경보 성적표(부실 사건 대비) · 경보 뒤 초과수익률"],
  ] },
  { title: "내려받기 (CSV)", rows: [
    ["GET /export/alerts.csv", "경보 목록 — /alerts 와 같은 조건, 최대 50,000행"],
    ["GET /export/companies/{corpCode}/metrics.csv", "한 기업의 모든 기간 · 지표 (분자 · 분모의 표준계정 · 기준 · 금액)"],
    ["GET /export/exposure.csv", "기업별 노출 요약 (days · unsoldPer1kHh)"],
    ["GET /export/companies/{corpCode}/pf-sites.csv", "가장 최근 정기보고서의 PF 사업장"],
  ] },
  { title: "기타", rows: [
    ["GET /exposure", "기업별 수주 · 보증 · PF 노출"],
    ["GET /meta", "지표 정의 · 공시 분류 사전 · 데이터 출처"],
    ["GET /dashboard", "대시보드 요약"],
  ] },
];

const EVIDENCE: [string, string][] = [
  ["ruleCode · ruleVersion", "평가한 규칙과 그 버전 — 파라미터를 바꾸면 새 버전, 옛 경보는 옛 버전을 가리킴"],
  ["condition · params", "사람이 읽는 조건식과 그때의 임계값"],
  ["observations[]", "조건을 참으로 만든 관측값 (기간 · 값 · 분자/분모 금액)"],
  ["sources[]", "원천 — type(DART · KOSIS · RONE · SGIS · RTMS) · rceptNo(접수번호) · table(통계표) · period"],
  ["calcRunId", "규칙 평가 실행 ID (ops.calc_run)"],
];

const LINEAGE: [string, string][] = [
  ["metrics[]", "근거에 들어간 지표 행 — 값이 없으면 status 에 사유 (MISSING · ZERO_DENOM · NEG_EQUITY · INCONSISTENT)"],
  ["statements[]", "표준계정 값 — basis POINT(시점) · CUM(누적) · QTR(분기 = 누적 차분, derivedFrom 에 두 누적값)"],
  ["statements[].lines[]", "그 값을 만든 DART 재무제표 응답의 줄(재무제표 · 줄 번호 · 계정명 · 금액)과 고른 매핑 규칙(mapId · matchType · pattern)"],
  ["statements[].reproduced", "같은 보고서를 지금의 매핑 규칙으로 다시 표준화한 값 = 저장된 값인지. false 면 매핑이 바뀐 뒤 아직 재계산 전"],
  ["stats[]", "지역 지표를 만든 통계 행 — role OWN(그 지역) · CHILD(일반구 행, 시 값은 합 · 평균)"],
  ["filings[]", "공시 원문 링크 · 구조화한 값(facts) · 파서 버전 · 상태"],
  ["runs[]", "RULE(규칙 평가) · METRIC(지표 계산) · JOB(배치 실행) · COLLECT(통계 수집) — 시작 · 끝 · 상태"],
];

export default function Developers() {
  const [origin, setOrigin] = useState("http://localhost:3400");
  useEffect(() => setOrigin(window.location.origin), []);
  const base = `${origin}/api/v1`;
  const code = "block whitespace-pre overflow-x-auto rounded-lg bg-page border border-line px-3 py-2 text-xs tabular";
  return (
    <Layout title="API 안내">
      <PageTitle title="API 안내 · 근거 내려받기" sub="화면이 쓰는 읽기 API 를 그대로 공개합니다. 키 없이 GET 으로 부르고, 모든 경보는 원천 재무제표 줄까지 거슬러 올라갈 수 있습니다." />
      <div className="space-y-5">
        <Card title="시작하기">
          <ul className="text-sm space-y-1.5 list-disc pl-5">
            <li>기본 주소 <code className="tabular">{base}</code> — 응답은 JSON(UTF-8), 금액은 원 단위 숫자</li>
            <li>읽기(GET)는 로그인 없이. 경보 확인(PATCH)은 분석가 로그인, 규칙 · 배치 · 매핑 변경은 관리자</li>
            <li>한도: IP 당 분당 1,200 요청, CSV 내려받기는 분당 30회 — 넘으면 <code>429</code> + <code>Retry-After</code>(초)</li>
            <li>오류는 <code>{"{ code, message, traceId }"}</code> — traceId 는 서버 로그의 같은 요청을 찾는 값</li>
            <li>전체 명세(OpenAPI): <a className="text-accent hover:underline" href="/swagger-ui/index.html">Swagger UI</a> · <a className="text-accent hover:underline" href="/v3/api-docs">/v3/api-docs</a></li>
          </ul>
        </Card>
        <Card title="예시">
          <div className="space-y-3 text-sm">
            <div><div className="section-label">높음 심각도의 열린 기업 경보</div>
              <code className={code}>{`curl -s '${base}/alerts?targetType=COMPANY&severity=HIGH&status=OPEN,ACK&size=20'`}</code></div>
            <div><div className="section-label">경보 하나의 근거 계보 — 원천 줄 · 매핑 규칙 · 실행</div>
              <code className={code}>{`curl -s '${base}/alerts/{alertId}/lineage'`}</code></div>
            <div><div className="section-label">한 기업의 모든 지표를 CSV 로</div>
              <code className={code}>{`curl -sOJ '${base}/export/companies/{corpCode}/metrics.csv'`}</code></div>
          </div>
        </Card>
        {GROUPS.map((g) => (
          <Card key={g.title} title={g.title} pad={false}>
            <div className="table-wrap">
              <table className="data-table">
                <tbody>{g.rows.map(([p, d]) => (
                  <tr key={p}><td className="whitespace-nowrap font-medium tabular align-top w-[1%]">{p}</td><td className="text-ink2">{d}</td></tr>))}</tbody>
              </table>
            </div>
          </Card>
        ))}
        <div className="grid lg:grid-cols-2 gap-5">
          <Card title="근거(evidence) 필드" sub="GET /alerts/{alertId} 의 evidence" pad={false}>
            <table className="data-table"><tbody>{EVIDENCE.map(([k, d]) => (
              <tr key={k}><td className="whitespace-nowrap font-medium tabular align-top w-[1%]">{k}</td><td className="text-ink2">{d}</td></tr>))}</tbody></table>
          </Card>
          <Card title="근거 계보(lineage) 필드" sub="GET /alerts/{alertId}/lineage" pad={false}>
            <table className="data-table"><tbody>{LINEAGE.map(([k, d]) => (
              <tr key={k}><td className="whitespace-nowrap font-medium tabular align-top w-[1%]">{k}</td><td className="text-ink2">{d}</td></tr>))}</tbody></table>
          </Card>
        </div>
        <Card title="CSV 규칙">
          <ul className="text-sm space-y-1.5 list-disc pl-5">
            <li><Prose text="UTF-8 BOM 으로 시작 — 엑셀에서 한글이 바로 보임. 줄바꿈 CRLF (RFC 4180)" /></li>
            <li>열 이름 = JSON 필드 이름. 숫자 칸은 따옴표 없이, 글자 칸은 큰따옴표로 감쌈</li>
            <li>수식 주입 방어(OWASP): 글자 칸의 첫 글자가 = + - @ · 탭 · 줄바꿈(전각 포함)이면 앞에 작은따옴표 <code>&apos;</code> 를 붙임</li>
            <li>경보 CSV 는 최대 50,000행 — 응답 헤더 <code>X-Total-Count</code> · <code>X-Truncated</code> 로 잘렸는지 알 수 있음</li>
          </ul>
        </Card>
        <Card title="호환 정책 (v1)">
          <ul className="text-sm space-y-1.5 list-disc pl-5">
            <li>/api/v1 안에서는 필드를 <b>더하기만</b> 합니다 — 받는 쪽은 모르는 필드를 무시해 주세요</li>
            <li>필드 이름 바꾸기 · 지우기 · 의미 바꾸기는 새 경로(/api/v2)로만, v1 은 그동안 유지</li>
            <li>상태 코드 · 규칙 코드 같은 열거 값은 늘어날 수 있습니다</li>
            <li>값이 없을 때는 0 이나 추정값 대신 빈 값(null)과 사유 코드를 둡니다</li>
          </ul>
        </Card>
        <p className="text-[11px] text-muted"><span className="block">공시·공공통계 기반 모니터링 예시이며 투자 권유·신용평가가 아닙니다.</span><span className="block">원천 데이터의 이용 조건은 각 제공처를 따릅니다.</span></p>
      </div>
    </Layout>
  );
}
