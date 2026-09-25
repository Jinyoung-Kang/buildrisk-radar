// k6 부하 테스트 — web(Next 프록시) → api 경로로 조회 API 를 섞어 호출 (make load)
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
    "http_req_duration{kind:geo}": ["p(95)<1000"],
    checks: ["rate>0.99"],
  },
  summaryTrendStats: ["avg", "med", "p(90)", "p(95)", "p(99)", "max"],
};

export function setup() {
  const companies = http.get(`${BASE}/api/v1/companies?size=200`).json("items").map((c) => c.corpCode);
  const regions = http.get(`${BASE}/api/v1/regions`).json("items").map((r) => r.regionCd);
  return { companies, regions };
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
  else res = http.get(`${BASE}/api/v1/regions/geojson?metric=UNSOLD_PER_1K_HH`, { tags: { kind: "geo", name: "geojson" } });
  check(res, { "2xx": (x) => x.status >= 200 && x.status < 300 });
}
