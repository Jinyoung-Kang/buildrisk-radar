// API 모듈 — 경로는 화면이 직접 만들던 것과 같아야 함(서버 캐시 키 · 허용 값이 경로에 묶임), 클라이언트의 경로 검사 · CSRF 재시도
import assert from "node:assert/strict";
import { afterEach, describe, it } from "node:test";
import { api, ApiError, qs } from "@/lib/api/client";
import { commands, downloads, queries } from "@/lib/api/endpoints";

describe("queries · downloads 경로", () => {
  it("조회 조건의 순서 · 빈 값 생략 · 인코딩이 예전과 같음", () => {
    assert.equal(queries.companies({ q: undefined, sort: "debtRatio", page: 2, size: 100 }).path, "/companies?sort=debtRatio&page=2&size=100");
    assert.equal(queries.companies({ q: "대우", sort: "", page: 0, size: 100 }).path, "/companies?q=%EB%8C%80%EC%9A%B0&page=0&size=100");
    assert.equal(queries.alerts({ status: "OPEN,ACK", severity: undefined, targetType: "REGION", page: 0, size: 100 }).path,
      "/alerts?status=OPEN%2CACK&targetType=REGION&page=0&size=100");
    assert.equal(queries.companyFinancials("00122694", "OFS").path, "/companies/00122694/financials?fsDiv=OFS");
    assert.equal(queries.companyFinancials("00122694").path, "/companies/00122694/financials");
    assert.equal(queries.exposure("365", "5").path, "/exposure?days=365&unsoldPer1kHh=5");
    assert.equal(queries.inbox({ unreadOnly: false, page: 0, size: 30 }).path, "/me/notifications?unreadOnly=false&page=0&size=30");
    assert.equal(queries.batchExecutions(undefined).path, "/batch/executions?limit=60");
    assert.equal(queries.regionBoundaries("abc").path, "/regions/boundaries?v=abc");
  });
  it("경로 조각은 인코딩 — 화면 파라미터가 다른 경로로 새지 않게", () => {
    assert.equal(queries.company("../admin").path, "/companies/..%2Fadmin");
    assert.equal(queries.regionSeries("11/../x").path, "/regions/11%2F..%2Fx/series");
    assert.equal(downloads.pfSitesCsv("a/b"), "/api/v1/export/companies/a%2Fb/pf-sites.csv");
    assert.equal(downloads.alertsCsv({ status: "OPEN" }), "/api/v1/export/alerts.csv?status=OPEN");
  });
  it("qs: 비었으면 물음표 없음", () => {
    assert.equal(qs({ a: undefined, b: null, c: "" }), "");
    assert.equal(qs({ a: 0, b: false }), "?a=0&b=false");
  });
});

describe("client", () => {
  const real = globalThis.fetch;
  afterEach(() => { globalThis.fetch = real; });

  it("상위 경로 · 프로토콜 상대 · 역슬래시는 보내기 전에 거부", async () => {
    let called = 0;
    globalThis.fetch = (async () => { called++; return new Response("{}"); }) as typeof fetch;
    for (const bad of ["//evil.example/x", "/a/../b", "/a\\b", "relative"]) {
      await assert.rejects(api(bad), (e: unknown) => e instanceof ApiError && e.status === 400, bad);
    }
    assert.equal(called, 0);
  });

  it("CSRF 토큰이 만료되면(403 CSRF_INVALID) 새로 받아 한 번만 다시 보냄", async () => {
    const calls: string[] = [];
    globalThis.fetch = (async (url: string, init?: RequestInit) => {
      calls.push(`${init?.method ?? "GET"} ${url}`);
      if (url.endsWith("/auth/me")) return new Response("{}");
      return new Response(JSON.stringify({ code: "CSRF_INVALID", message: "x" }), { status: 403 });
    }) as typeof fetch;
    await assert.rejects(commands.markRead({ all: true }), (e: unknown) => e instanceof ApiError && e.code === "CSRF_INVALID");
    assert.deepEqual(calls, ["POST /api/v1/me/notifications/read", "GET /api/v1/auth/me", "POST /api/v1/me/notifications/read"]);
  });

  it("오류 본문의 code · message · traceId 를 ApiError 로", async () => {
    globalThis.fetch = (async () => new Response(JSON.stringify({ code: "ALERT_NOT_FOUND", message: "경보 9", traceId: "t1" }), { status: 404 })) as typeof fetch;
    await assert.rejects(api("/alerts/9"), (e: unknown) => e instanceof ApiError && e.status === 404 && e.code === "ALERT_NOT_FOUND" && e.traceId === "t1");
  });
});
