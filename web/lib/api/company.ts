// 기업 — /companies/* 조회 · CSV 주소 — 화면은 `import * as companyApi from "@/lib/api/company"` (ADR-030)
import { qs, query, BASE } from "./client";
import type { CompanyFilings, CompanyPf, CompanyRow, CompanySummary, Disclosure, Financials, Metrics, Page, Prices, Sites } from "./types";

const e = encodeURIComponent;
export const list = (p: { q?: string; sort?: string; page: number; size: number }) => query<Page<CompanyRow>>(`/companies${qs(p)}`);
export const summary = (corp: string) => query<CompanySummary>(`/companies/${e(corp)}`);
export const metrics = (corp: string) => query<Metrics>(`/companies/${e(corp)}/metrics`);
export const financials = (corp: string, fsDiv?: string) => query<Financials>(`/companies/${e(corp)}/financials${qs({ fsDiv })}`);
export const disclosures = (corp: string) => query<Disclosure[]>(`/companies/${e(corp)}/disclosures`);
export const filings = (corp: string) => query<CompanyFilings>(`/companies/${e(corp)}/filings`);
export const pf = (corp: string) => query<CompanyPf>(`/companies/${e(corp)}/pf`);
export const prices = (corp: string) => query<Prices>(`/companies/${e(corp)}/prices?days=1095`);
export const sites = (corp: string) => query<Sites>(`/companies/${e(corp)}/sites`);
export const metricsCsv = (corp: string) => `${BASE}/export/companies/${e(corp)}/metrics.csv`;
export const pfSitesCsv = (corp: string) => `${BASE}/export/companies/${e(corp)}/pf-sites.csv`;
