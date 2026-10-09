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

test("관리자: 구독 A 를 고치다 B 의 '바꾸기'를 눌러도 B 에는 B 의 값이 저장 (다른 구독을 덮어쓰지 않음)", async ({ page }) => {
  test.skip(!ADMIN, "E2E_ADMIN_PASSWORD 미설정");
  // 폼에 key 가 없어 A 의 입력값이 남은 채 B 로 저장됐음 (VERIFICATION 123)
  await page.goto("/login?next=/notifications");
  await page.getByLabel("아이디").fill("admin");
  await page.getByLabel("비밀번호").fill(ADMIN!);
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.getByRole("heading", { name: "받은 알림" })).toBeVisible();

  const t = Date.now();
  const a = `E2E 가 ${t}`, b = `E2E 나 ${t}`;
  for (const [name, sev] of [[a, "HIGH"], [b, "LOW"]] as const) {
    await page.getByRole("button", { name: "+ 새 구독" }).click();
    await page.getByLabel("이름").first().fill(name);
    await page.getByLabel("심각도").selectOption(sev);
    await page.getByRole("button", { name: "구독 만들기" }).click();
    await expect(page.getByRole("listitem").filter({ hasText: name })).toBeVisible();
  }
  const itemA = page.getByRole("listitem").filter({ hasText: a });
  const itemB = page.getByRole("listitem").filter({ hasText: b });
  await itemA.getByRole("button", { name: "바꾸기" }).click();
  await expect(page.getByRole("form", { name: "구독 바꾸기" }).getByLabel("이름")).toHaveValue(a);
  await itemB.getByRole("button", { name: "바꾸기" }).click();
  const form = page.getByRole("form", { name: "구독 바꾸기" });
  await expect(form.getByLabel("이름")).toHaveValue(b);
  await expect(form.getByLabel("심각도")).toHaveValue("LOW");
  await form.getByRole("button", { name: "저장" }).click();
  await expect(itemA).toContainText("높음만");
  await expect(itemB).toContainText("모든 심각도");
  for (const item of [itemA, itemB]) {
    page.once("dialog", (d) => d.accept());
    await item.getByRole("button", { name: "지우기" }).click();
    await expect(item).toHaveCount(0);
  }
});

