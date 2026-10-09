// ESLint 9 flat config — Next 16 의 권장 규칙(core-web-vitals · typescript). `next lint` 는 16 에서 없어져 ESLint CLI 로 (ADR-028)
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";

const config = [
  ...nextVitals,
  ...nextTs,
  { ignores: [".next/**", "node_modules/**", "test-results/**", "playwright-report/**", "e2e-failures/**", "next-env.d.ts"] },
  {
    // 화면은 경로 · fetch 를 직접 다루지 않고 @/lib/api 의 queries · commands · downloads 와 Hook 을 씀 (ADR-030)
    files: ["pages/**/*.{ts,tsx}", "components/**/*.{ts,tsx}"],
    ignores: ["pages/api/**"],
    rules: {
      "no-restricted-imports": ["error", { patterns: [{ group: ["@/lib/api/*", "**/lib/api/*"], message: "화면은 @/lib/api 의 queries · commands 를 씁니다 (ADR-030)" }] }],
      "no-restricted-globals": ["error", { name: "fetch", message: "API 호출은 @/lib/api 의 queries · commands 로 (ADR-030)" }],
    },
  },
  {
    // 규칙 · 변환은 React · 화면 · 네트워크 · 브라우저를 모르는 순수 함수로 (ADR-030) — API 응답 타입만 import type
    files: ["lib/domain/**/*.ts"],
    rules: {
      "@typescript-eslint/no-restricted-imports": ["error", { patterns: [
        { group: ["react", "react-dom", "next", "next/*", "@/components/*", "@/lib/hooks/*", "@/lib/api/client", "@/lib/api/endpoints"],
          message: "lib/domain 은 순수 함수만 — React · 화면 · 네트워크를 모름 (ADR-030)" },
        { group: ["@/lib/api"], allowTypeImports: true, message: "lib/domain 은 API 응답 타입만 import type 으로 (ADR-030)" },
      ] }],
      "no-restricted-globals": ["error", "window", "document", "fetch", "localStorage", "sessionStorage", "navigator"],
    },
  },
];

export default config;
