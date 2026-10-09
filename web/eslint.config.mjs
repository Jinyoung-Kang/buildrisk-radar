// ESLint 9 flat config — Next 16 의 권장 규칙(core-web-vitals · typescript). `next lint` 는 16 에서 없어져 ESLint CLI 로 (ADR-028)
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const config = [
  ...nextVitals,
  ...nextTs,
  { ignores: [".next/**", "node_modules/**", "test-results/**", "playwright-report/**", "e2e-failures/**", "next-env.d.ts"] },
  {
    rules: {
      // 효과 안의 setState(6곳 — 마운트 뒤 브라우저 값 읽기 · 데이터 불러오기)는 화면 로직을 Hook 으로 옮기는 단계(개선 계획 P5)에서
      // useSyncExternalStore · 요청 Hook 으로 바꾸며 다시 켬
      "react-hooks/set-state-in-effect": "off",
    },
  },
  {
    // 화면은 경로 · fetch 를 직접 다루지 않고 @/lib/api 의 queries · commands · downloads 와 Hook 을 씀 (ADR-030)
    files: ["pages/**/*.{ts,tsx}", "components/**/*.{ts,tsx}"],
    ignores: ["pages/api/**"],
    rules: {
      "no-restricted-imports": ["error", { patterns: [{ group: ["@/lib/api/*", "**/lib/api/*"], message: "화면은 @/lib/api 의 queries · commands 를 씁니다 (ADR-030)" }] }],
      "no-restricted-globals": ["error", { name: "fetch", message: "API 호출은 @/lib/api 의 queries · commands 로 (ADR-030)" }],
    },
  },
];

export default config;
