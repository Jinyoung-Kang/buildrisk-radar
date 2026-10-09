import { expect, test, type Page } from "@playwright/test";

// 화면 상태 · 오류 처리 (개선 계획 FE-*) — 응답을 일부러 늦추거나 실패시켜 경합 · 오류 경로를 재현

/** 이 경로의 응답을 release() 할 때까지 붙잡음 */
async function hold(page: Page, url: string | RegExp) {
  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  await page.route(url, async (route) => { await gate; await route.continue(); });
  return () => release();
}

test("조건을 바꾸면 새 응답이 올 때까지 옛 숫자에 새 이름표를 붙이지 않음 (VERIFICATION 140)", async ({ page }) => {
  await page.goto("/backtest?tab=price");
  await expect(page.getByText("규칙별 60거래일 초과수익률")).toBeVisible({ timeout: 15_000 });
  const release = await hold(page, "**/api/v1/backtest?horizon=20");
  await page.getByRole("radiogroup", { name: "보유 기간" }).getByRole("radio", { name: "20거래일", exact: true }).click();
  // 20거래일 응답이 오기 전 — 60거래일 숫자가 '20거래일' 제목 아래 보이면 안 됨
  await page.waitForTimeout(300);
  await expect(page.getByText("규칙별 20거래일 초과수익률")).toHaveCount(0);
  release();
  await expect(page.getByText("규칙별 20거래일 초과수익률")).toBeVisible({ timeout: 15_000 });
});

/** 이 경로를 500 으로 */
async function fail(page: Page, url: string | RegExp) {
  await page.route(url, (route) => route.fulfill({ status: 500, contentType: "application/json",
    body: JSON.stringify({ code: "INTERNAL_ERROR", message: "시험용 실패", traceId: "e2e" }) }));
}

test("조회가 실패하면 무한 로딩 · '데이터 없음' 대신 오류를 보임 (VERIFICATION 141)", async ({ page, request }) => {
  const corp = (await (await request.get("/api/v1/companies?size=50")).json()).items[0].corpCode as string;
  const region = (await (await request.get("/api/v1/regions")).json()).items.find((r: { value?: number }) => r.value != null).regionCd as string;
  const cases: [string, string | RegExp][] = [
    [`/regions/${region}`, `**/api/v1/regions/${region}/series`],
    ["/about", "**/api/v1/meta"],
    [`/companies/${corp}?tab=financials`, `**/api/v1/companies/${corp}/metrics`],
    [`/companies/${corp}?tab=financials`, `**/api/v1/companies/${corp}/financials*`],
    [`/companies/${corp}?tab=disclosures`, `**/api/v1/companies/${corp}/disclosures`],
    ["/admin/batch", "**/api/v1/batch/executions?*"],
    ["/admin/mapping", "**/api/v1/mapping/account-rules"],
  ];
  for (const [path, api] of cases) {
    await page.unrouteAll();
    await fail(page, api);
    await page.goto(path);
    await expect(page.getByRole("alert").filter({ hasText: "시험용 실패" }), `${path} ← ${api}`).toBeVisible({ timeout: 10_000 });
  }
});

test("잘못된 주소의 기업 · 지역은 계속 불러오는 대신 찾을 수 없다고 보임 (VERIFICATION 141)", async ({ page }) => {
  for (const path of ["/regions/abcde", "/companies/12ab"]) {
    await page.goto(path);
    await expect(page.getByText(/형식이 아닙니다/), path).toBeVisible({ timeout: 10_000 });
  }
});

test("불러오는 동안은 '데이터 없음'이 아닌 불러오는 중 (VERIFICATION 141)", async ({ page, request }) => {
  const corp = (await (await request.get("/api/v1/companies?size=50")).json()).items[0].corpCode as string;
  const release = await hold(page, `**/api/v1/companies/${corp}/disclosures`);
  await page.goto(`/companies/${corp}?tab=disclosures`);
  await page.waitForTimeout(500);
  await expect(page.getByText("표시할 공시가 없습니다.")).toHaveCount(0);
  await expect(page.getByText("불러오는 중…").first()).toBeVisible();
  release();
});
