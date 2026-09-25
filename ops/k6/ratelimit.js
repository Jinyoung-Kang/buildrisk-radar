// 레이트리밋 확인 — 한 클라이언트(IP)가 분당 한도(기본 1,200)를 넘기면 429 + Retry-After
import http from "k6/http";
import { check } from "k6";
import { Counter } from "k6/metrics";

const BASE = __ENV.BASE || "http://web:3400";
const limited = new Counter("rate_limited");
export const options = { vus: 20, duration: "20s", thresholds: { rate_limited: ["count>0"] } };

export default function () {
  const res = http.get(`${BASE}/api/v1/rules`);
  if (res.status === 429) {
    limited.add(1);
    check(res, { "Retry-After 헤더": (r) => !!r.headers["Retry-After"], "오류 형식": (r) => r.json("code") === "TOO_MANY_REQUESTS" });
  } else {
    check(res, { "200": (r) => r.status === 200 });
  }
}
