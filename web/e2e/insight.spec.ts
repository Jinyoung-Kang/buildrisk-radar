import { expect, test } from "@playwright/test";

// ADR-015 · 016 · 017 화면
test("수주·보증 노출: 기업별 표와 방법 설명", async ({ page }) => {
  await page.goto("/exposure");
  await expect(page.getByRole("heading", { name: /수주·보증 노출/ })).toBeVisible();
  await expect(page.getByRole("columnheader", { name: "위험 지역 비중" })).toBeVisible();
  await expect(page.locator("tbody tr").first()).toBeVisible();
});

test("경보 검증: 규칙별 표 · 비교 기준 · 한계", async ({ page }) => {
  await page.goto("/backtest?h=60");
  await expect(page.getByRole("heading", { name: /경보 검증/ })).toBeVisible();
  await expect(page.getByText(/비교 기준 — 신호 없는 모든 창/)).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText("한계 (결과를 읽기 전에)")).toBeVisible();
});

test("기업 상세: 탭 전환(키보드 포함)과 수주·보증 탭", async ({ page }) => {
  await page.goto("/companies");
  await page.locator("tbody tr a").first().click();
  await expect(page.getByRole("tablist", { name: "기업 정보" })).toBeVisible();
  await page.getByRole("tab", { name: "수주·보증" }).click();
  await expect(page).toHaveURL(/tab=filings/);
  await expect(page.getByText("수주 계약 (단일판매ㆍ공급계약)")).toBeVisible();
  await page.getByRole("tab", { name: "수주·보증" }).press("ArrowRight");
  await expect(page.getByRole("tab", { name: "주가·경보" })).toHaveAttribute("aria-selected", "true");
});

test("좁은 화면: 메뉴 버튼으로 이동", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");
  await page.getByRole("button", { name: "메뉴 열기" }).click();
  await page.locator("#mobile-nav").getByRole("link", { name: "경보 검증" }).click();
  await expect(page).toHaveURL(/\/backtest/);
});
