import { expect, test, type Page } from "@playwright/test";

// 화면 상태 · 오류 처리 (개선 계획 FE-*) — 응답을 일부러 늦추거나 실패시켜 경합 · 오류 경로를 재현

/** 이 경로의 응답을 release() 할 때까지 붙잡음 */
async function hold(page: Page, url: string | RegExp) {
  let release!: () => void;
  const gate = new Promise<void>((r) => { release = r; });
  await page.route(url, async (route) => { await gate; await route.continue(); });
  return () => release();
}

type KakaoFake = { polys: { __l: Record<string, (e?: unknown) => void> }[]; tip: { content: string; shown: boolean } };

/** 카카오 지도 SDK 대역 — 외부 망 · 키 없이 지도 컴포넌트의 이벤트 · 툴팁을 시험. 폴리곤과 툴팁 상태를 window.__kakaoFake 에 남김 */
const FAKE_KAKAO = `(() => {
  const noop = () => {};
  const tip = { content: "", shown: false };
  window.__kakaoFake = { polys: [], tip };
  window.kakao = { maps: {
    load: (cb) => cb(),
    LatLng: function (lat, lng) { this.lat = lat; this.lng = lng; },
    LatLngBounds: function () { this.extend = noop; this.isEmpty = () => false; },
    Map: function () { this.addControl = noop; this.relayout = noop; this.setBounds = noop; },
    ZoomControl: function () {}, ControlPosition: { RIGHT: 0 },
    CustomOverlay: function () { this.setContent = (c) => { tip.content = c; }; this.setPosition = noop; this.setMap = (m) => { tip.shown = !!m; }; },
    Polygon: function () { this.setOptions = noop; this.setMap = noop; window.__kakaoFake.polys.push(this); },
    event: { addListener: (t, type, fn) => { (t.__l ||= {})[type] = fn; } },
  } };
})();`;

async function fakeKakao(page: Page) {
  await page.route("**/api/config", (route) => route.fulfill({ json: { kakaoJsKey: "e2e-fake" } }));
  await page.route("https://dapi.kakao.com/**", (route) => route.fulfill({ contentType: "text/javascript", body: FAKE_KAKAO }));
}

/** 첫 폴리곤에 마우스를 올렸다 내렸다 하고(over=true 면 올린 채로) 툴팁 상태를 돌려줌 */
function kakaoTip(page: Page, over?: boolean) {
  return page.evaluate((o) => {
    const f = (window as unknown as { __kakaoFake: KakaoFake }).__kakaoFake;
    if (o !== undefined) { f.polys[0].__l.mouseout(); if (o) f.polys[0].__l.mouseover({ latLng: {} }); }
    return { ...f.tip };
  }, over);
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

test("목록 화면도 조회가 실패하면 오류만 — 불러오는 중 · '없음' 안내를 함께 보이지 않음 (VERIFICATION 163)", async ({ page }) => {
  const cases: [string, string, string][] = [
    ["/companies", "**/api/v1/companies?*", "대상 기업이 없습니다."],
    ["/alerts", "**/api/v1/alerts?*", "조건에 맞는 경보가 없습니다."],
    ["/exposure", "**/api/v1/exposure?*", "기업이 없습니다."],
  ];
  for (const [path, api, empty] of cases) {
    await page.unrouteAll();
    await fail(page, api);
    await page.goto(path);
    await expect(page.getByRole("alert").filter({ hasText: "시험용 실패" }), path).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(300);
    await expect(page.getByText("불러오는 중…"), path).toHaveCount(0);
    await expect(page.getByText(empty, { exact: true }), path).toHaveCount(0);
  }
});

test("규칙 · 매핑 · 성적표 · 주가 반응 · 감사 · 알림 화면도 조회가 실패하면 오류만 — 불러오는 중을 함께 보이지 않음 (VERIFICATION 197)", async ({ page }) => {
  // [화면, 실패시킬 조회, 로그인 역할(가짜 세션)] — 163 이 다루지 않은 나머지
  const cases: [string, string, string[]][] = [
    ["/admin/rules", "**/api/v1/rules", []],
    ["/admin/mapping", "**/api/v1/mapping/unmapped", []],
    ["/backtest", "**/api/v1/scorecard?*", []],
    ["/backtest?tab=price", "**/api/v1/backtest?*", []],
    ["/admin/audit", "**/api/v1/admin/audit?*", ["ADMIN"]],
    ["/notifications", "**/api/v1/me/notifications?*", ["ANALYST"]],
    ["/notifications", "**/api/v1/me/subscriptions", ["ANALYST"]],
    ["/notifications", "**/api/v1/me/watch", ["ANALYST"]],
  ];
  for (const [path, api, roles] of cases) {
    await page.unrouteAll();
    if (roles.length) await page.route("**/api/v1/auth/me", (route) => route.fulfill({ json: { authenticated: true, username: "e2e", roles } }));
    await fail(page, api);
    await page.goto(path);
    await expect(page.getByRole("alert").filter({ hasText: "시험용 실패" }).first(), `${path} ${api}`).toBeVisible({ timeout: 10_000 });
    await page.waitForTimeout(300);
    await expect(page.getByText("불러오는 중…"), `${path} ${api}`).toHaveCount(0);
  }
});

test("실행 중인 Job 의 상세를 열어 두면 상세도 폴링으로 갱신 (VERIFICATION 198)", async ({ page }) => {
  // 목록은 3초마다 다시 부르는데 상세는 처음 값(실행 중 · 그때 건수)에 멈춰 있었음
  const job = { name: "ruleEvalJob", title: "규칙 평가", source: "DB", schedule: "-", requirement: "-", keyConfigured: true,
    running: true, heartbeat: "2026-10-11T12:00:00", stale: false, last: { jobExecutionId: 999, status: "STARTED", startTime: "2026-10-11T11:59:00", endTime: "null" } };
  await page.route("**/api/v1/batch/jobs", (route) => route.fulfill({ json: { jobs: [job], workers: [], quota: [], dartDailyLimit: 15000, schedulingEnabled: false } }));
  await page.route("**/api/v1/batch/executions?*", (route) => route.fulfill({ json: [] }));
  await page.route("**/api/v1/batch/requests?*", (route) => route.fulfill({ json: [] }));
  let detail = 0;
  await page.route("**/api/v1/batch/executions/999", (route) => {
    detail++;
    return route.fulfill({ json: { jobExecutionId: 999, jobName: "ruleEvalJob", status: "STARTED", durationSec: 60, params: "", steps: [], skips: [], sameInstance: [] } });
  });
  await page.goto("/admin/batch");
  await page.getByRole("button", { name: /실행 중|STARTED/ }).first().click();
  await expect.poll(() => detail, { timeout: 3_000 }).toBeGreaterThanOrEqual(1);
  await page.waitForTimeout(7_000);                                   // 폴링 3초 × 2
  expect(detail).toBeGreaterThanOrEqual(2);
});

test("검색어를 바꾸고 결과가 오기 전에 Enter 를 누르면 이전 검색어의 결과로 가지 않음 (VERIFICATION 199)", async ({ page }) => {
  // 한글 IME 는 Enter 로 마지막 글자를 확정 — 입력이 멈춘 뒤(200 ms) 부르는 검색 결과가 이전 검색어의 것인 채로 Enter 가 첫 결과로 이동했음
  await page.route("**/api/v1/search?*", async (route) => {
    const q = new URL(route.request().url()).searchParams.get("q") ?? "";
    const hit = q === "가" ? { key: "00000001", name: "가건설" } : { key: "00000002", name: "나건설" };
    return route.fulfill({ json: { q, companies: [hit], regions: [] } });
  });
  await page.route(/\/api\/v1\/companies\/0000000[12]$/, (route) => route.fulfill({ status: 404, json: { code: "COMPANY_NOT_FOUND", message: "x" } }));
  await page.goto("/");
  const box = page.getByRole("searchbox", { name: "거래 건설사 찾기" });
  await box.fill("가");
  await expect(page.getByRole("link", { name: /가건설/ })).toBeVisible();
  await box.fill("나");
  await box.press("Enter");                                           // 200 ms 안 — 아직 '가'의 결과
  await page.waitForTimeout(600);
  expect(page.url()).not.toContain("/companies/00000001");
});

test("경보 확인 뒤 목록을 다시 불러오다 실패해도 보이던 목록은 남음 (VERIFICATION 167)", async ({ page }) => {
  // 163 에서 오류면 목록을 숨기게 해, 같은 조건을 다시 부르다 실패하면(이전 값은 남겨 둠) 행 없이 쪽 넘김만 남았음
  await page.route("**/api/v1/auth/me", (route) => route.fulfill({ json: { authenticated: true, username: "e2e", roles: ["ANALYST"] } }));
  await page.route(/\/api\/v1\/alerts\/\d+$/, (route) =>
    route.request().method() === "PATCH" ? route.fulfill({ json: {} }) : route.continue());
  let lists = 0;
  await page.route("**/api/v1/alerts?*", (route) => (++lists === 1 ? route.continue() : route.fulfill({ status: 500,
    contentType: "application/json", body: JSON.stringify({ code: "INTERNAL_ERROR", message: "시험용 실패", traceId: "e2e" }) })));
  await page.goto("/alerts?status=OPEN");
  const first = page.locator("tbody tr.clickable").first();
  const hasAlerts = await first.waitFor({ timeout: 10_000 }).then(() => true).catch(() => false);
  test.skip(!hasAlerts, "열린 경보가 없음 — 배치를 먼저 실행");
  const rows = await page.locator("tbody tr.clickable").count();
  await first.click();
  await page.getByRole("button", { name: "확인(ACK)" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "시험용 실패" })).toBeVisible();
  expect(lists).toBe(2);
  await expect(page.locator("tbody tr.clickable")).toHaveCount(rows);
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
  // 요약(머리말)이 먼저 그려진 뒤 공시 칸만 본다 — 페이지 전체의 '불러오는 중'과 섞이지 않게
  const panel = page.locator("#panel-disclosures");
  await expect(panel).toBeVisible({ timeout: 10_000 });
  await page.waitForTimeout(500);
  await expect(panel.getByText("표시할 공시가 없습니다.")).toHaveCount(0);
  await expect(panel.getByText("불러오는 중…")).toBeVisible();
  release();
  await expect(panel.getByText("불러오는 중…")).toHaveCount(0);
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

test("실행 중인 Job 이 있을 때의 배치 화면 갱신은 탭이 보일 때만 (VERIFICATION 146)", async ({ page }) => {
  // 실행 중인 Job 하나가 있는 것처럼 — 실제 Job 은 돌리지 않음
  let calls = 0;
  await page.route("**/api/v1/batch/jobs", async (route) => {
    calls++;
    const res = await route.fetch();
    const body = await res.json();
    body.jobs[0] = { ...body.jobs[0], running: true };
    await route.fulfill({ response: res, json: body });
  });
  await page.goto("/admin/batch");
  await expect.poll(() => calls, { timeout: 10_000 }).toBeGreaterThanOrEqual(2);   // 보이는 동안은 3초마다
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", { value: "hidden", configurable: true });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await page.waitForTimeout(500);
  const hidden = calls;
  await page.waitForTimeout(7_000);
  expect(calls - hidden).toBe(0);
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", { value: "visible", configurable: true });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect.poll(() => calls, { timeout: 5_000 }).toBeGreaterThan(hidden);       // 다시 보이면 곧바로 갱신
  await page.unrouteAll({ behavior: "ignoreErrors" });                            // 끝난 뒤 도착하는 폴링 요청은 무시
});

test("접근성: 배치 실행은 키보드로 열고, 규칙 변경 사유 입력에 이름이 있음 (VERIFICATION 147)", async ({ page }) => {
  await page.goto("/admin/batch");
  const open = page.getByRole("button", { name: /^실행 #\d+ 상세$/ }).first();
  await open.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByText("Step 실행")).toBeVisible();
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  await login(page, "/admin/rules");
  await page.getByRole("button", { name: "파라미터 수정" }).first().click();
  await expect(page.getByLabel("변경 사유")).toBeVisible();
});

test("다른 기업으로 옮기면 앞 기업에서 고른 연결/별도가 남지 않음 (VERIFICATION 148)", async ({ page, request }) => {
  const items = (await (await request.get("/api/v1/companies?size=50")).json()).items;
  const [a, b] = [items[0].corpCode as string, items[1].corpCode as string];
  await page.goto(`/companies/${a}?tab=financials`);
  const fs = () => page.getByRole("radiogroup", { name: "연결·별도" });
  await fs().getByRole("radio", { name: "별도" }).click();
  await expect(fs().getByRole("radio", { name: "별도" })).toHaveAttribute("aria-checked", "true");
  // 같은 화면 안에서 이동 (검색 · 링크와 같은 클라이언트 이동)
  await page.evaluate((p) => (window as unknown as { next: { router: { push: (u: string) => void } } }).next.router.push(p), `/companies/${b}?tab=financials`);
  await expect(page).toHaveURL(new RegExp(`/companies/${b}`));
  await expect(fs().getByRole("radio", { name: "자동(연결 우선)" })).toHaveAttribute("aria-checked", "true");
});

test("검색 결과가 없으면 배치를 실행하라는 안내 대신 검색어에 맞는 기업이 없다고 보임 (VERIFICATION 165)", async ({ page }) => {
  await page.goto("/companies");
  await expect(page.locator("tbody tr").first()).toBeVisible({ timeout: 10_000 });
  await page.getByLabel("기업 검색").fill("없는기업이름zzz");
  await expect(page.getByText("‘없는기업이름zzz’에 맞는 기업이 없습니다.")).toBeVisible();
  await expect(page.getByText(/corpCodeSyncJob/)).toHaveCount(0);
});

test("기업 검색 결과도 쪽을 넘길 수 있음 (VERIFICATION 149)", async ({ page }) => {
  // 유니버스가 100곳보다 적어 실제로는 쪽이 하나 — 응답을 흉내 냄
  const seen: string[] = [];
  await page.route("**/api/v1/companies?*", (route) => {
    const u = new URL(route.request().url());
    seen.push(`${u.searchParams.get("q") ?? ""}:${u.searchParams.get("page")}`);
    const items = Array.from({ length: 100 }, (_, i) => ({ corpCode: String(90000000 + i), corpName: `가상건설${i}`, openAlerts: 0 }));
    return route.fulfill({ json: { items, total: 250, page: Number(u.searchParams.get("page")), size: 100 } });
  });
  await page.goto("/companies");
  await page.getByLabel("기업 검색").fill("가상");
  await expect.poll(() => seen.at(-1)).toBe("가상:0");
  await page.getByRole("button", { name: "다음" }).click();
  await expect.poll(() => seen.at(-1)).toBe("가상:1");
});

test("꺼진 웹훅으로 보내는 구독을 고칠 때 그 웹훅이 선택된 채 보임 (VERIFICATION 149)", async ({ page }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  await login(page, "/notifications");
  const hook = { webhookId: 990001, name: "꺼둔 웹훅", kind: "SLACK", urlHint: "https://hooks.slack.com/…", signed: false, enabled: false,
    createdBy: "admin", createdAt: "2026-10-09T00:00:00+09:00", pending: 0, dead: 0, subscriptions: 1 };
  await page.route("**/api/v1/admin/webhooks", (route) => route.fulfill({ json: { available: true, items: [hook] } }));
  await page.route("**/api/v1/me/subscriptions", (route) => route.fulfill({ json: [{ subscriptionId: 990002, name: "웹훅 구독", minSeverity: "HIGH",
    watchOnly: false, channel: "WEBHOOK", webhookId: hook.webhookId, webhookName: hook.name, enabled: true, createdAt: hook.createdAt }] }));
  await page.reload();
  await page.getByRole("listitem").filter({ hasText: "웹훅 구독" }).getByRole("button", { name: "바꾸기" }).click();
  const select = page.getByRole("combobox", { name: "웹훅" });
  await expect(select).toHaveValue(String(hook.webhookId));
  await expect(select.locator("option:checked")).toContainText("꺼짐");
});

test("카카오 지도 SDK 를 못 받아도 다시 열 때 스크립트를 겹쳐 넣지 않음 (VERIFICATION 149)", async ({ page }) => {
  // 키가 없는 환경에서는 스크립트를 넣기 전에 멈춰 아무것도 시험하지 않고 통과했음 — 시험용 키로 SDK 요청까지 가게 함
  await page.route("**/api/config", (route) => route.fulfill({ json: { kakaoJsKey: "e2e-fake" } }));
  let attempts = 0;
  await page.route("https://dapi.kakao.com/**", (route) => { attempts++; return route.abort(); });
  const scripts = () => page.locator("script[src*='dapi.kakao.com']").count();
  await page.goto("/regions");
  await expect.poll(() => attempts).toBe(1);
  await page.waitForTimeout(500);
  expect(await scripts()).toBeLessThanOrEqual(1);
  const nav = (p: string) => page.evaluate((u) => (window as unknown as { next: { router: { push: (x: string) => void } } }).next.router.push(u), p);
  await nav("/companies");
  await expect(page).toHaveURL(/\/companies/);
  await nav("/regions");
  await expect(page).toHaveURL(/\/regions/);
  await expect.poll(() => attempts).toBe(2);                                          // 다시 열면 다시 시도하고
  await page.waitForTimeout(500);
  expect(await scripts()).toBeLessThanOrEqual(1);                                   // 다시 시도해도 한 개를 넘지 않음
});

test("탭 주소로 바로 열면 다른 탭을 잠깐 그리며 그 탭의 요청을 보내지 않음 (VERIFICATION 151)", async ({ page }) => {
  const scorecard: string[] = [];
  page.on("request", (r) => { if (r.url().includes("/api/v1/scorecard")) scorecard.push(r.url()); });
  await page.goto("/backtest?tab=price");
  await expect(page.getByText(/비교 기준 — 신호 없는 모든 창/)).toBeVisible({ timeout: 15_000 });
  expect(scorecard).toEqual([]);
});

test("지표를 바꾸는 동안 지도 툴팁에 옛 값을 새 지표 이름으로 보이지 않음 (VERIFICATION 162)", async ({ page }) => {
  await fakeKakao(page);
  await page.goto("/regions");
  await expect.poll(() => page.evaluate(() => (window as unknown as { __kakaoFake?: KakaoFake }).__kakaoFake?.polys.length ?? 0),
    { timeout: 15_000 }).toBeGreaterThan(0);
  expect((await kakaoTip(page, true)).shown).toBe(true);
  const select = page.getByLabel("지표");
  const current = await select.inputValue();
  const other = await select.locator("option").evaluateAll((os, cur) => {
    const o = (os as HTMLOptionElement[]).find((x) => x.value !== cur)!;
    return { value: o.value, name: o.text.replace(/ \([^)]*\)$/, "") };
  }, current);
  const release = await hold(page, new RegExp(`/api/v1/regions\\?metric=${other.value}`));
  await select.selectOption(other.value);
  await page.waitForTimeout(300);
  expect((await kakaoTip(page)).shown).toBe(false);          // 떠 있던 옛 툴팁은 닫힘
  expect((await kakaoTip(page, true)).shown).toBe(false);    // 새 값이 오기 전에는 띄우지 않음
  release();
  await expect.poll(async () => (await kakaoTip(page, true)).content).toContain(other.name);
  expect((await kakaoTip(page, true)).shown).toBe(true);
});
