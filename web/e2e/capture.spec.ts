import { test } from "@playwright/test";

// README 화면 캡처: CAPTURE=1 E2E_CHANNEL=chrome npx playwright test capture
test.skip(!process.env.CAPTURE, "CAPTURE=1 일 때만");
test.use({ colorScheme: "light", viewport: { width: 1440, height: 900 } });

const OUT = "../docs/images";
const shots: [string, string, (p: import("@playwright/test").Page) => Promise<void>][] = [
  ["dashboard", "/", async () => {}],
  ["regions", "/regions", async (p) => { await p.waitForTimeout(2500); }],
  ["company", "/companies/00153861", async (p) => { await p.waitForTimeout(1200); }],
  ["companies", "/companies", async () => {}],
  ["region-card", "/regions?metric=UNSOLD_PER_1K_HH", async (p) => { await p.waitForTimeout(2000); await p.locator("ol button").first().click(); await p.waitForTimeout(2000); }],
  ["region-price", "/regions?metric=PRICE_IDX_3M_CHG", async (p) => { await p.waitForTimeout(3000); }],
  ["alerts", "/alerts?targetType=COMPANY", async (p) => { await p.locator("tbody tr.clickable").first().click(); await p.waitForTimeout(1000); }],
  ["batch", "/admin/batch", async (p) => { await p.locator("tbody tr").first().click(); await p.waitForTimeout(800); }],
  ["rules", "/admin/rules", async () => {}],
  ["mapping", "/admin/mapping", async () => {}],
  ["about", "/about", async () => {}],
];

for (const [name, path, act] of shots) {
  test(`capture ${name}`, async ({ page }) => {
    await page.goto(path);
    await page.waitForLoadState("networkidle");
    await act(page);
    await page.screenshot({ path: `${OUT}/${name}.png` });
  });
}
