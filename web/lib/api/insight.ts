// 대시보드 · 노출 · 검증(백테스트 · 성적표) · 검색 · 메타 — 화면은 `import * as insightApi from "@/lib/api/insight"` (ADR-030)
import { qs, query, BASE } from "./client";
import type { Backtest, Exposure, Meta, Row, Scorecard, SearchResult } from "./types";
export const dashboard = () => query<Row>("/dashboard");
export const meta = () => query<Meta>("/meta");
export const search = (q: string) => query<SearchResult>(`/search${qs({ q })}`);
/** 기간 · 기준을 주지 않으면 서버가 경보 R-X01 의 최신 파라미터를 씀 (VERIFICATION 208) */
export const exposure = (days?: number | string, unsoldPer1kHh?: string) => query<Exposure>(`/exposure${qs({ days, unsoldPer1kHh })}`);
export const backtest = (horizon: number | string) => query<Backtest>(`/backtest?horizon=${horizon}`);
export const scorecard = (horizonMonths: number | string) => query<Scorecard>(`/scorecard?horizonMonths=${horizonMonths}`);
export const exposureCsv = (days?: number | string, unsoldPer1kHh?: string) => `${BASE}/export/exposure.csv${qs({ days, unsoldPer1kHh })}`;
