import { expect, test } from "@playwright/test";

// ADR-025 알림 — 관리자 비밀번호는 환경변수로만 (E2E_ADMIN_PASSWORD)
const ADMIN = process.env.E2E_ADMIN_PASSWORD;

test("익명: 알림 화면은 로그인 안내, 머리말에 종이 없음", async ({ page }) => {
  await page.goto("/notifications");
  await expect(page.getByRole("heading", { name: "알림", exact: true })).toBeVisible();
  await expect(page.getByText(/로그인한 사용자별로 받습니다/)).toBeVisible();
  await expect(page.getByRole("link", { name: /^알림/ })).toHaveCount(0);
});

test("관리자: 종 → 알림 화면 · 구독 만들고 지우기 · 내부망 웹훅 거부", async ({ page }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  await page.goto("/login?next=/companies");
  await page.getByLabel("아이디").fill("admin");
  await page.getByLabel("비밀번호").fill(ADMIN!);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page).toHaveURL(/\/companies/);
  await page.getByRole("link", { name: /^알림/ }).click();
  await expect(page).toHaveURL(/\/notifications/);
  await expect(page.getByRole("heading", { name: "받은 알림" })).toBeVisible();

  const name = `E2E 구독 ${Date.now()}`;
  await page.getByRole("button", { name: "+ 새 구독" }).click();
  await page.getByLabel("이름").first().fill(name);
  await page.getByLabel("심각도").selectOption("HIGH");
  await page.getByRole("button", { name: "구독 만들기" }).click();
  const item = page.getByRole("listitem").filter({ hasText: name });
  await expect(item).toContainText("높음만");
  page.once("dialog", (d) => d.accept());
  await item.getByRole("button", { name: "지우기" }).click();
  await expect(item).toHaveCount(0);

  const form = page.getByRole("form", { name: "웹훅 등록" });
  await form.getByLabel("이름").fill("E2E 내부망");
  await form.getByLabel("주소 (https)").fill("https://169.254.169.254/latest");
  await form.getByRole("button", { name: "웹훅 등록" }).click();
  await expect(page.getByRole("alert").filter({ hasText: "내부망 주소로는 보낼 수 없음" })).toBeVisible();
});
