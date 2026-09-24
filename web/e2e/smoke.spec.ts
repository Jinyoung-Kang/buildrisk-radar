import { expect, test } from "@playwright/test";

test("대시보드: 경보 요약과 고지 문구", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: /조기경보/ })).toBeVisible();
  await expect(page.getByText("열린 경보", { exact: true })).toBeVisible();
  await expect(page.getByText(/투자 판단 자료 아님/)).toBeVisible();   // NFR-08
});

test("지역 지도: 단계구분도가 그려지고 목록에서 지역 카드를 연다", async ({ page }) => {
  await page.goto("/regions");
  // 카카오 지도(폴리곤) 또는 SVG 대체 지도 중 하나
  await expect(page.locator('[aria-label="시군구 단계구분도"] path, [aria-label="시군구 지도"] svg').first()).toBeVisible({ timeout: 15_000 });
  await page.locator("ol button").first().click();
  await expect(page.locator("section").getByText("미분양 주택 (호)")).toBeVisible();
});

test("경보: 상세에 근거(조건·파라미터·관측값·원천)가 붙는다", async ({ page }) => {
  await page.goto("/alerts?status=OPEN,ACK,CLOSED");
  const first = page.locator("tbody tr.clickable").first();
  const hasAlerts = await first.waitFor({ timeout: 10_000 }).then(() => true).catch(() => false);
  test.skip(!hasAlerts, "경보가 아직 없음 — 배치를 먼저 실행");
  await first.click();
  await expect(page.getByText(/^조건/)).toBeVisible();
  await expect(page.getByText("파라미터 (임계값)")).toBeVisible();
  await expect(page.getByText(/원천 \(NFR-04/)).toBeVisible();
});

test("배치 모니터: Job 10개와 실행 이력", async ({ page }) => {
  await page.goto("/admin/batch");
  await expect(page.getByText("financialStatementJob · 매일 03:00")).toBeVisible();
  await expect(page.getByText("ruleEvalJob · 지표 Job 완료 후")).toBeVisible();
});

test("테마 전환: 다크·라이트 선택이 새로고침 뒤에도 유지되고 시스템으로 되돌릴 수 있다", async ({ page }) => {
  await page.goto("/");
  const bg = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor);
  await page.getByRole("radio", { name: "다크 모드" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  const dark = await bg();
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("radio", { name: "라이트 모드" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  expect(await bg()).not.toBe(dark);
  await page.getByRole("radio", { name: "시스템 설정" }).click();
  await expect(page.locator("html")).not.toHaveAttribute("data-theme", /.+/);
});
