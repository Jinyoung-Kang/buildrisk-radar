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
    // 실패한 테스트만 기록을 남김 — 트레이스(화면 · DOM · 네트워크 · 콘솔)와 스크린샷. 통과하면 지움
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  // HTML 보고서(실패 메시지 · 트레이스 링크). 실행마다 test-results 가 비워지므로 make e2e 가 실패 기록을 e2e-failures/ 로 옮겨 둠
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
});
