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

test("익명: 규칙 화면은 보이지만 수정 버튼 대신 로그인 안내", async ({ page }) => {
  await page.goto("/admin/rules");
  await expect(page.getByRole("heading", { name: "규칙 관리" })).toBeVisible();
  await expect(page.getByText(/관리자 로그인 후 변경할 수 있습니다/)).toBeVisible();
  await expect(page.getByRole("button", { name: "파라미터 수정" })).toHaveCount(0);
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
