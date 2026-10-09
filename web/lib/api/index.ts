// API 응답 타입 · 오류. 호출은 기능별 모듈(company · region · alert · insight · notify · ops · session)을 화면이 직접 import —
// `import * as companyApi from "@/lib/api/company"`. 한 모듈로 모으면 공통 묶음에 모든 경로가 들어가 화면마다 첫 로드가 커짐 (ADR-030)
export * from "./types";
export { ApiError } from "./client";
export type { Query } from "./client";
export type { SubscriptionBody } from "./notify";
