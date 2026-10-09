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
