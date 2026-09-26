// k6 부하 테스트 — edge(nginx) → api 경로로 조회 API 를 화면 사용 비율대로 섞어 호출 (make load)
//   BASE: 기본 http://edge:8080 (compose 네트워크 안, 실제 진입점 경유)
//   조회만 부름 — 변경 API 는 부하 대상이 아님
import http from "k6/http";
import { check } from "k6";

const BASE = __ENV.BASE || "http://edge:8080";

export const options = {
  scenarios: {
    browse: {
      executor: "ramping-vus",
      startVUs: 0,
      stages: [
        { duration: "20s", target: 50 },
        { duration: "60s", target: 50 },
        { duration: "10s", target: 0 },
      ],
    },
  },
  thresholds: {
    "http_req_duration{kind:api}": ["p(95)<300"],
    "http_req_duration{kind:geo}": ["p(95)<300"],   // 경계 재검증(304) — 본문 없이 ETag 비교만 (API 와 같은 목표)
    checks: ["rate>0.99"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  const companies = http.get(`${BASE}/api/v1/companies?size=200`).json("items").map((c) => c.corpCode);
  const list = http.get(`${BASE}/api/v1/regions`);
  const regions = list.json("items").map((r) => r.regionCd);
  const metrics = http.get(`${BASE}/api/v1/meta`).json("metrics").filter((m) => m.target === "REGION").map((m) => m.code);
  // 지도는 경계(버전 URL, 1년 immutable)를 한 번 받고 지표 바꿀 때 값 목록(~5KB)만 받음 — 재방문은 ETag 재검증
  const boundaryVersion = list.json("boundaryVersion");
  const etag = http.get(`${BASE}/api/v1/regions/boundaries?v=${boundaryVersion}`).headers["Etag"];
  return { companies, regions, metrics, boundaryVersion, etag };
}

const pick = (a) => a[Math.floor(Math.random() * a.length)];

export default function (d) {
  const r = Math.random();
  let res;
  if (r < 0.15) res = http.get(`${BASE}/api/v1/dashboard`, { tags: { kind: "api", name: "dashboard" } });
  else if (r < 0.30) res = http.get(`${BASE}/api/v1/companies`, { tags: { kind: "api", name: "companies" } });
  else if (r < 0.50) res = http.get(`${BASE}/api/v1/companies/${pick(d.companies)}`, { tags: { kind: "api", name: "company" } });
  else if (r < 0.60) res = http.get(`${BASE}/api/v1/companies/${pick(d.companies)}/filings`, { tags: { kind: "api", name: "filings" } });
  else if (r < 0.70) res = http.get(`${BASE}/api/v1/regions/${pick(d.regions)}/series`, { tags: { kind: "api", name: "region" } });
  else if (r < 0.80) res = http.get(`${BASE}/api/v1/alerts?status=OPEN,ACK`, { tags: { kind: "api", name: "alerts" } });
  else if (r < 0.88) res = http.get(`${BASE}/api/v1/exposure`, { tags: { kind: "api", name: "exposure" } });
  else if (r < 0.93) res = http.get(`${BASE}/api/v1/backtest?horizon=60`, { tags: { kind: "api", name: "backtest" } });
  else if (r < 0.98) res = http.get(`${BASE}/api/v1/regions?metric=${pick(d.metrics)}`, { tags: { kind: "api", name: "regions" } });
  else res = http.get(`${BASE}/api/v1/regions/boundaries?v=${d.boundaryVersion}`,
    { headers: { "If-None-Match": d.etag }, tags: { kind: "geo", name: "boundaries-304" } });
  check(res, { "2xx·304": (x) => (x.status >= 200 && x.status < 300) || x.status === 304 });
}
