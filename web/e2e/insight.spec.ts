import { expect, test } from "@playwright/test";

// ADR-015 · 016 · 017 화면
test("수주·보증 노출: 기업별 표와 방법 설명", async ({ page }) => {
  await page.goto("/exposure");
  await expect(page.getByRole("heading", { name: /수주·보증 노출/ })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "위험 지역 비중", exact: true })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "분양 위험 지역 비중" })).toBeVisible();     // 청약홈 (ADR-032)
  await expect(page.locator("tbody tr").first()).toBeVisible();
});

test("PF 우발채무: 노출 표를 PF 비율 순으로 정렬하고 기업 상세에서 종합요약표 · 사업장 · 두 가지 대조", async ({ page }) => {
  await page.goto("/exposure");
  await page.getByRole("radio", { name: "PF/자기자본" }).click();
  await expect(page.getByRole("columnheader", { name: /PF 보증 \/ 자기자본/ })).toBeVisible();
  await page.locator("tbody tr").first().getByRole("link").first().click();
  await expect(page).toHaveURL(/tab=filings/);
  await expect(page.getByText("PF 보증 (회사 노출)")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("heading", { name: "종합요약표" })).toBeVisible();
  await expect(page.getByText("사업장 합", { exact: true })).toBeVisible();
  await expect(page.getByText("본문 서술", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: /사업장별 PF 보증 \d+곳/ })).toBeVisible();
  await expect(page.getByRole("heading", { name: "분기별 정기보고서" })).toBeVisible();
});

test("사업장 카드: 기업 수주·보증 탭에서 동 단위 시장 · 해석 범위 · 원문", async ({ page }) => {
  await page.goto("/exposure");
  await page.getByRole("radio", { name: "수주 금액" }).click();
  await page.locator("tbody tr").first().getByRole("link").first().click();
  await expect(page).toHaveURL(/tab=filings/);
  const card = page.getByRole("heading", { name: "사업장 카드" });
  await expect(card).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/현재 공급계약 \d+건 중 동·지번까지 \d+건/)).toBeVisible();
  await page.getByRole("radio", { name: "전체" }).last().click();                       // 해석 못 한 곳도 사유와 함께
  await expect(page.getByText(/원문:/).first()).toBeVisible();
});

test("경보 검증 · 성적표: 부실 사건 대비 규칙별 정밀도 · 재현율과 사건 목록", async ({ page }) => {
  await page.goto("/backtest");
  await expect(page.getByRole("heading", { name: /경보 검증/ })).toBeVisible();
  await expect(page.getByRole("tab", { name: "부실 사건 적중" })).toHaveAttribute("aria-selected", "true");
  await expect(page.getByText("규칙별 성적")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("img", { name: /정밀도 .*95% 구간/ }).first()).toBeVisible();   // 막대에도 숫자를 읽어 줌
  await expect(page.getByText(/부실 사건 \d+건과 그 전의 경보/)).toBeVisible();
  // 응답이 늦어도 설명과 숫자가 같은 기간을 가리켜야 함 — 전에는 고르자마자 이름만 24개월로 바뀌었음 (VERIFICATION 103).
  // 문구는 표 설명 줄만 (방법 설명에도 같은 말이 있어 응답이 빠르면 두 곳이 걸려 간헐 실패했음)
  await page.route((u) => u.pathname.endsWith("/api/v1/scorecard") && u.searchParams.get("horizonMonths") === "24",
    async (r) => { await new Promise((ok) => setTimeout(ok, 1500)); await r.continue(); });
  await page.getByRole("radio", { name: "24개월" }).click();
  await expect(page.getByText(/^정밀도 = 경보 뒤 12개월 안에/)).toBeVisible();          // 아직 12개월 숫자
  await expect(page.getByText(/^정밀도 = 경보 뒤 24개월 안에/)).toBeVisible({ timeout: 10_000 });
});

test("경보 검증 · 주가 반응: 규칙별 표 · 비교 기준 · 한계", async ({ page }) => {
  await page.goto("/backtest?tab=price");
  await expect(page.getByText(/비교 기준 — 신호 없는 모든 창/)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("한계 (결과를 읽기 전에)").first()).toBeVisible();
});

test("기업 상세: 탭 전환(키보드 포함)과 수주·보증 탭", async ({ page, request }) => {
  // 상장사 — 비상장(2026-10 수동 포함 9곳)은 주가·경보 탭이 없음
  const items = (await (await request.get("/api/v1/companies?size=100")).json()).items as { corpCode: string; listed: boolean }[];
  await page.goto(`/companies/${items.find((c) => c.listed)!.corpCode}`);
  await expect(page.getByRole("tablist", { name: "기업 정보" })).toBeVisible();
  await page.getByRole("tab", { name: "수주·보증" }).click();
  await expect(page).toHaveURL(/tab=filings/);
  await expect(page.getByText("수주 계약 (단일판매ㆍ공급계약)")).toBeVisible();
  await page.getByRole("tab", { name: "수주·보증" }).press("ArrowRight");
  await expect(page.getByRole("tab", { name: "주가·경보" })).toHaveAttribute("aria-selected", "true");
});

test("분양 탭 · 지역 분양 카드: 청약홈 공고 · 위험 지역 분양 비중 · 입주 예정 (ADR-032)", async ({ page, request }) => {
  // 분양 공고가 가장 많은 대상 기업 — 노출 API 의 분양 열로 고름
  const listed = new Set(((await (await request.get("/api/v1/companies?size=100")).json()).items as { corpCode: string; listed: boolean }[])
    .filter((c) => c.listed).map((c) => c.corpCode));
  const items = (await (await request.get("/api/v1/exposure")).json()).items as { corpCode: string; presaleNotices: number }[];
  const corp = [...items].filter((x) => listed.has(x.corpCode)).sort((a, b) => b.presaleNotices - a.presaleNotices)[0];
  expect(corp.presaleNotices, "청약홈 공고가 연결된 대상 기업이 있어야 함 — presaleSyncJob").toBeGreaterThan(0);
  await page.goto(`/companies/${corp.corpCode}?tab=price`);
  await page.getByRole("tab", { name: "주가·경보" }).press("ArrowRight");                 // 키보드 순서: 주가·경보 → 분양
  await expect(page.getByRole("tab", { name: "분양" })).toHaveAttribute("aria-selected", "true");
  await expect(page).toHaveURL(/tab=presales/);
  await expect(page.getByText("분양 공고 (청약홈)")).toBeVisible();
  await expect(page.getByText(/이상 지역 분양 비중/)).toBeVisible();
  await expect(page.getByText("경보 R-X02 와 같은 기준", { exact: false })).toBeVisible();
  await page.locator("table a[href^='/regions/']").first().click();
  await expect(page).toHaveURL(/\/regions\/\d{5}/);
  await expect(page.getByText("분양 공고 (청약홈)")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("앞으로 12개월 입주 예정")).toBeVisible();
});

test("비상장 기업: 종목 대신 '비상장', 주가·경보 탭 없이 재무 · 분양 탭 (2026-10 수동 포함)", async ({ page, request }) => {
  const items = (await (await request.get("/api/v1/companies?size=100")).json()).items as { corpCode: string; listed: boolean }[];
  const unlisted = items.find((c) => !c.listed);
  test.skip(!unlisted, "비상장 대상 기업이 없음 — companyProfileJob 뒤");
  await page.goto(`/companies/${unlisted!.corpCode}`);
  await expect(page.getByText(/비상장 · \d{8}/)).toBeVisible();
  await expect(page.getByRole("tab", { name: "주가·경보" })).toHaveCount(0);
  await expect(page.getByRole("tab", { name: "분양" })).toBeVisible();
});

test("좁은 화면: 메뉴 버튼으로 이동", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "메뉴 열기" }).click();
  await page.locator("#mobile-nav").getByRole("link", { name: "경보 검증" }).click();
  await expect(page).toHaveURL(/\/backtest/);
});

// ADR-027 근거 계보 · 내려받기 · API 안내
test("근거 계보: 경보 상세에서 원천 재무제표 줄 · 매핑 규칙 · 실행까지", async ({ page, request }) => {
  const res = await request.get("/api/v1/alerts?ruleCode=R-C02&status=OPEN,ACK,CLOSED&size=1");
  const id = (await res.json()).items[0].alertId as number;
  await page.goto(`/alerts?status=OPEN%2CACK%2CCLOSED&id=${id}`);
  await page.locator("summary", { hasText: "근거 계보" }).click();
  await expect(page.getByText("표준계정 → 원천 재무제표 줄")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("columnheader", { name: "고른 매핑 규칙" }).first()).toBeVisible();
  await expect(page.getByText(/누적 − \d{4}Q\d 누적/).first()).toBeVisible();              // 분기값 = 누적 차분
  await expect(page.getByText("✓ 지금의 매핑 규칙으로 다시 계산해도 같은 값").first()).toBeVisible();
  await expect(page.getByRole("cell", { name: /financialStatementJob/ })).toBeVisible();
});

test("CSV 내려받기: 경보 목록 버튼이 BOM 으로 시작하는 CSV 를 받는다", async ({ page }) => {
  await page.goto("/alerts");
  const [download] = await Promise.all([page.waitForEvent("download"), page.getByRole("link", { name: "CSV" }).click()]);
  expect(download.suggestedFilename()).toMatch(/^alerts-\d{8}\.csv$/);
  const body = await (await import("node:fs/promises")).readFile(await download.path());
  expect(body.subarray(0, 3)).toEqual(Buffer.from([0xef, 0xbb, 0xbf]));
  expect(body.toString("utf8", 3, 40)).toContain('"alertId","ruleCode"');
});

test("API 안내: 메뉴에서 열고 근거 계보 · CSV 규칙 · 호환 정책", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("link", { name: "API", exact: true }).first().click();
  await expect(page.getByRole("heading", { name: /API 안내/ })).toBeVisible();
  await expect(page.getByRole("cell", { name: "GET /alerts/{alertId}/lineage" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "CSV 규칙" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "호환 정책 (v1)" })).toBeVisible();
});

// 사용자별 진입점 (대시보드)
test("사용자별 진입점: 건설사 · 시군구를 찾아 상세로, 투자 카드는 성적표 요약", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "질문에서 시작하기" })).toBeVisible();
  await page.getByPlaceholder("건설사 이름 (예: 대우)").fill("대우");
  await page.getByRole("list", { name: "거래 건설사 찾기 결과" }).getByRole("link", { name: /대우건설/ }).click();
  await expect(page).toHaveURL(/\/companies\/00124540$/);

  await page.goto("/");
  const buyer = page.getByPlaceholder("시군구 또는 시공사 (예: 수원)");
  await buyer.fill("수원");
  await expect(page.getByRole("list", { name: "시군구 또는 시공사 찾기 결과" })).toBeVisible();
  await buyer.press("Enter");                                                       // Enter = 첫 결과
  await expect(page).toHaveURL(/\/regions\/41110$/);

  await page.goto("/");
  await expect(page.getByText("부실 사건 중 미리 잡은 수")).toBeVisible();
  await expect(page.getByText(/^\d+\/\d+$/)).toBeVisible();
});
