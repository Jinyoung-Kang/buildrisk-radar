import { defineConfig } from "@playwright/test";

// 스택(make up)이 떠 있는 상태에서 실행: npx playwright test
export default defineConfig({
  testDir: "./e2e",
  timeout: 30_000,
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:3400",
    channel: process.env.E2E_CHANNEL,          // 예: chrome (설치된 Google Chrome 사용)
    viewport: { width: 1440, height: 900 },
    locale: "ko-KR",
  },
  reporter: [["list"]],
});
