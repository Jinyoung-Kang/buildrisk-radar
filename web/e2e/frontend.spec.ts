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

const ADMIN = process.env.E2E_ADMIN_PASSWORD;

async function login(page: Page, next = "/") {
  await page.goto(`/login?next=${encodeURIComponent(next)}`);
  await page.getByLabel("아이디").fill("admin");
  await page.getByLabel("비밀번호").fill(ADMIN!);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();
}

test("로그인 전에 보낸 로그인 확인이 늦게 와도 로그인 상태를 덮어쓰지 않음 (VERIFICATION 142)", async ({ page }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  let first = true;
  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  await page.route("**/api/v1/auth/me", (route) => {
    // 화면을 열 때 보낸 첫 확인(익명)을 붙잡음 — 처리기 안에서 기다리지 않음(다음 요청이 막히지 않게)
    if (first) { first = false; void gate.then(() => route.continue()); return; }
    return route.continue();
  });
  await page.goto("/login?next=/companies");
  await page.getByLabel("아이디").fill("admin");
  await page.getByLabel("비밀번호").fill(ADMIN!);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/companies/);
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();
  release();                                                       // 익명 응답이 이제 도착
  await page.waitForTimeout(800);
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();
});

test("읽지 않은 알림 수: 먼저 보낸 요청의 늦은 응답이 새 숫자를 덮지 않음 (VERIFICATION 142)", async ({ page }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  await login(page, "/");
  let n = 0;
  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  await page.route("**/api/v1/me/notifications/unread-count", (route) => {
    // 처리기 안에서 기다리면 같은 경로의 다음 요청도 막히므로, 첫 요청은 나중에 응답만 함
    if (++n === 1) { void gate.then(() => route.fulfill({ json: { unread: 5 } })); return; }   // 먼저 보낸 요청 — 늦게, 옛 숫자
    return route.fulfill({ json: { unread: 2 } });
  });
  await page.evaluate(() => window.dispatchEvent(new Event("br:notify-changed")));
  await page.evaluate(() => window.dispatchEvent(new Event("br:notify-changed")));
  await expect(page.getByRole("link", { name: "알림 — 읽지 않음 2건" })).toBeVisible();
  release();
  await page.waitForTimeout(800);
  await expect(page.getByRole("link", { name: "알림 — 읽지 않음 2건" })).toBeVisible();
});

test("로그아웃 요청이 실패해도 처리되지 않은 오류 없이 실제 로그인 상태를 다시 확인 (VERIFICATION 142)", async ({ page }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  await login(page, "/");
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.route("**/api/v1/auth/logout", (route) => route.fulfill({ status: 500, json: { code: "INTERNAL_ERROR", message: "x" } }));
  await page.getByRole("button", { name: "로그아웃" }).click();
  await page.waitForTimeout(800);
  expect(errors).toEqual([]);
  await expect(page.getByRole("button", { name: "로그아웃" })).toBeVisible();   // 서버 세션이 남았으니 로그인 상태로 보여야 함
});

test("변경 요청: 실패 뒤 다시 해서 성공하면 이전 오류가 사라짐 (VERIFICATION 142)", async ({ page, request }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  const open = (await (await request.get("/api/v1/alerts?status=OPEN&size=50")).json()).items[0];
  test.skip(!open, "열린 경보 없음");
  await login(page, `/alerts?id=${open.alertId}`);
  let n = 0;
  await page.route(`**/api/v1/alerts/${open.alertId}`, async (route) => {
    if (route.request().method() === "PATCH" && ++n === 1) {
      return route.fulfill({ status: 409, json: { code: "CONFLICT", message: "시험용 충돌" } });
    }
    if (route.request().method() === "PATCH") {
      return route.fulfill({ json: { ...open, status: "ACK" } });                  // 실제 데이터는 바꾸지 않음
    }
    return route.continue();
  });
  await page.getByRole("button", { name: "확인(ACK)" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "시험용 충돌" })).toBeVisible();
  await page.getByRole("button", { name: "확인(ACK)" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "시험용 충돌" })).toHaveCount(0);
});
