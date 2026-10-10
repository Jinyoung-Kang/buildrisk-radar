import { expect, test } from "@playwright/test";

// ADR-013 — 조회는 공개, 변경은 로그인 + 역할. 관리자 비밀번호는 환경변수로만 받음 (E2E_ADMIN_PASSWORD)
const ADMIN = process.env.E2E_ADMIN_PASSWORD;

test("보안 헤더: 화면에 CSP · 클릭재킹 방지", async ({ page }) => {
  const res = await page.goto("/");
  const h = res!.headers();
  expect(h["content-security-policy"]).toContain("script-src 'self'");
  expect(h["x-frame-options"]).toBe("DENY");
  expect(h["x-content-type-options"]).toBe("nosniff");
});

test("edge: 대소문자만 다른 /API 경로도 api 가 받아 거부 (web 을 거쳐 클라이언트 IP 가 바뀌지 않게)", async ({ request }) => {
  // 끝 슬래시가 없는 '/API/v1' 도 — 'api/v1/' 로만 잡아 web 으로 갔음 (VERIFICATION 164)
  for (const p of ["/API/v1/dashboard", "/Api/v1/rules", "/SWAGGER-UI/index.html", "/API/v1", "/Api/V1?x=1"]) {
    const res = await request.get(p);
    expect(res.status(), p).toBe(401);
  }
  expect((await request.get("/api/v1/dashboard")).status()).toBe(200);
});

test("익명: 규칙 화면은 보이지만 수정 버튼 대신 로그인 안내", async ({ page }) => {
  await page.goto("/admin/rules");
  await expect(page.getByRole("heading", { name: "규칙 관리" })).toBeVisible();
  await expect(page.getByText(/관리자 로그인 후 변경할 수 있습니다/)).toBeVisible();
  await expect(page.getByRole("button", { name: "파라미터 수정" })).toHaveCount(0);
});

test("공개 데모: 로그인 링크 · 로그인 안내 대신 조회 전용 안내 (VERIFICATION 171)", async ({ page }) => {
  // AWS 는 앞단 프록시가 /api/ 의 GET · HEAD 가 아닌 요청(로그인 포함)을 막음 — 화면 설정만 데모로 바꿔 재현
  await page.route("**/api/config", (route) => route.fulfill({ json: { kakaoJsKey: "", demo: true } }));
  await page.goto("/admin/rules");
  await expect(page.getByRole("heading", { name: "규칙 관리" })).toBeVisible();
  await expect(page.getByText("데모에서는 조회만 가능합니다")).toBeVisible();
  await expect(page.getByText(/로그인 후 변경할 수 있습니다/)).toHaveCount(0);
  await expect(page.getByRole("link", { name: "로그인" })).toHaveCount(0);
  await page.goto("/notifications");
  await expect(page.getByText(/로그인한 사용자별로 받습니다\. 데모에서는 조회만 가능합니다/)).toBeVisible();
  await expect(page.getByRole("link", { name: "로그인" })).toHaveCount(0);
});

test("잘못된 비밀번호는 같은 메시지로 거절", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("아이디").fill("e2e-nobody");
  await page.getByLabel("비밀번호").fill("wrong-password-xyz");
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.locator("form").getByRole("alert")).toContainText("아이디 또는 비밀번호가 올바르지 않습니다");
});

test("관리자 로그인 → 수정 버튼 · 감사 로그 → 로그아웃", async ({ page }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  await page.goto("/login?next=/admin/rules");
  await page.getByLabel("아이디").fill("admin");
  await page.getByLabel("비밀번호").fill(ADMIN!);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/admin\/rules/);
  await expect(page.getByRole("button", { name: "파라미터 수정" }).first()).toBeVisible();
  const cookies = await page.context().cookies();
  const session = cookies.find((c) => c.name === "BR_SESSION");
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe("Strict");
  await page.goto("/admin/audit");
  await expect(page.getByText("LOGIN_SUCCESS").first()).toBeVisible();
  await page.getByRole("button", { name: "로그아웃" }).first().click();
  await expect(page.getByRole("link", { name: "로그인" }).first()).toBeVisible();
});

test("X-Forwarded-For 위조는 감사 로그 IP 를 바꾸지 못함 (edge 가 덮어씀)", async ({ page, request }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  const forged = "198.51.100.123";
  const res = await request.put("/api/v1/rules/R-C01", { headers: { "X-Forwarded-For": forged, "Content-Type": "application/json" }, data: {} });
  expect(res.status()).toBe(401);
  await page.goto("/login?next=/admin/audit");
  await page.getByLabel("아이디").fill("admin");
  await page.getByLabel("비밀번호").fill(ADMIN!);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/admin\/audit/);
  await expect(page.getByText(/^PUT \/api\/v1\/rules\//).first()).toBeVisible();      // 위조 요청도 감사됨
  await expect(page.getByText(forged)).toHaveCount(0);                                   // 그러나 위조 IP 는 기록되지 않음
});

test("관리자: 매핑 목록이 바뀌어도 규칙 추가는 그 줄의 계정과 입력값으로 (다른 계정에 붙지 않음)", async ({ page }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  // 줄 key 가 순번이라, 규칙을 더한 뒤 목록이 바뀌면 앞 줄에 쓰던 패턴이 다음 계정의 규칙으로 저장됐음 (VERIFICATION 124)
  const row = (corp: string, name: string, std: string, nameKo: string, sj: string, cand: string) =>
    ({ corp_code: corp, corp_name: name, std_code: std, name_ko: nameKo, sj_div: sj, periods: "2026Q2", candidates: [{ sj_div: sj, account_id: "x", account_nm: cand, thstrm_amount: 1 }] });
  const base = { accountMappingRate: 90, accountMappingByStd: [], accountNote: "", regionMappingBySource: [], regions: [],
    universe: { listed: 1, targets: 1, listed_without_induty: 0, byInduty: [] } };
  const first = { ...base, accounts: [row("00000001", "가건설", "BONDS", "사채", "BS", "사채"), row("00000002", "나건설", "REVENUE", "매출액", "IS", "공사수익")] };
  const after = { ...base, accounts: [row("00000002", "나건설", "REVENUE", "매출액", "IS", "공사수익")] };
  let calls = 0;
  await page.route("**/api/v1/mapping/unmapped", (r) => r.fulfill({ json: calls++ === 0 ? first : after }));
  const posted: Record<string, unknown>[] = [];
  await page.route("**/api/v1/mapping/account-rules", (r) => {
    if (r.request().method() !== "POST") return r.fallback();
    posted.push(r.request().postDataJSON());
    return r.fulfill({ json: { mapId: posted.length, next: "다시 표준화하면 반영" } });
  });

  await page.goto("/login?next=/admin/mapping");
  await page.getByLabel("아이디").fill("admin");
  await page.getByLabel("비밀번호").fill(ADMIN!);
  await page.getByRole("button", { name: "로그인" }).click();
  const first0 = page.getByRole("row").filter({ hasText: "가건설" });
  await first0.getByPlaceholder("계정명·패턴").fill("사채|유동성사채");
  await first0.getByRole("button", { name: "매핑 규칙 추가" }).click();
  const next0 = page.getByRole("row").filter({ hasText: "나건설" });
  await expect(page.getByRole("row").filter({ hasText: "가건설" })).toHaveCount(0);
  await expect(next0.getByPlaceholder("계정명·패턴")).toHaveValue("공사수익");
  await next0.getByRole("button", { name: "매핑 규칙 추가" }).click();
  await expect.poll(() => posted.length).toBe(2);
  expect(posted[0]).toMatchObject({ stdCode: "BONDS", pattern: "사채|유동성사채" });
  expect(posted[1]).toMatchObject({ stdCode: "REVENUE", sjDiv: "IS", pattern: "공사수익" });
});

