// 백엔드(Spring Boot) API 타입과 호출 도우미 — 브라우저는 같은 출처 /api/v1 로 부릅니다.

export type Severity = "HIGH" | "MEDIUM" | "LOW";
export type AlertStatus = "OPEN" | "ACK" | "CLOSED";
export type Page<T> = { items: T[]; total: number; page: number; size: number };

export type CompanyRow = {
  corpCode: string; corpName: string; stockCode?: string; corpCls?: string; indutyCode?: string;
  latestPeriod?: string; debtRatio?: number; debtRatioStatus?: string; interestCoverage?: number;
  interestCoverageStatus?: string; borrowingDep?: number; openAlerts: number; maxSeverity?: Severity;
  lastDisclosureDate?: string; lastDisclosure?: string;
};
export type MetricBrief = { code: string; nameKo: string; value?: number; unit: string; status: string; yoyPp?: number; formula: string };
export type AlertBrief = { alertId: number; ruleCode: string; ruleVersion?: number; severity: Severity; asOf: string; title: string; status: AlertStatus };
export type CompanySummary = {
  corpCode: string; corpName: string; stockCode?: string; corpCls?: string; indutyCode?: string; address?: string;
  ceo?: string; homepage?: string; accMt?: string; target: boolean; targetReason?: string;
  latest?: { periodKey: string; fsDiv?: string; rceptNo?: string; metrics: MetricBrief[] };
  alerts: AlertBrief[]; fetch: { reportsOk: number; reportsNoData: number; lastFetchedAt?: string };
  calcRunId?: string; disclaimer: string;
};
export type FinancialPoint = { periodKey: string; fsDiv: string; amount?: number; qtrAmount?: number; rceptNo?: string; sourceAccount?: string };
export type FinancialSeries = { stdCode: string; nameKo: string; sjDiv: string; flowType: string; unit: string; points: FinancialPoint[] };
export type Financials = { corpCode: string; fsDiv: string; availableFsDivs: string[]; basisNote: string; series: FinancialSeries[]; disclaimer: string };
export type MetricPoint = { periodKey: string; value?: number; status: string; components: Record<string, unknown> };
export type MetricSeries = { code: string; nameKo: string; unit: string; formula: string; higherIsRisk: boolean; points: MetricPoint[] };
export type Metrics = { corpCode: string; calcRunId?: string; metrics: MetricSeries[]; disclaimer: string };
export type Disclosure = { rceptNo: string; rceptDt: string; reportNm: string; eventType: string; eventKeyword?: string; flrNm?: string; rm?: string; url: string };

export type RegionRow = { regionCd: string; name: string; fullName: string; sidoCd: string; sidoName: string; value?: number; status?: string; alertCount: number };
export type RegionList = { metric: string; unit: string; period?: string; periods: string[]; items: RegionRow[]; calcRunId?: string; disclaimer: string };
export type RegionProps = { regionCd: string; name: string; fullName: string; sidoCd: string; sidoName: string; value: number | null; status: string; alertCount: number; lat: number; lon: number };
export type Geometry = { type: "Polygon" | "MultiPolygon"; coordinates: number[][][] | number[][][][] };
export type GeoJson = {
  meta: { metric: string; nameKo: string; unit: string; period?: string; calcRunId?: string; sources: string[] };
  type: "FeatureCollection"; features: { type: "Feature"; properties: RegionProps; geometry: Geometry }[];
};
export type RegionSeries = {
  regionCd: string; name: string; fullName: string; sidoName: string; children: string[];
  stats: { code: string; nameKo: string; unit: string; source: string; points: { period: string; value?: number }[] }[];
  metrics: { code: string; nameKo: string; unit: string; formula: string; points: { period: string; value?: number; status: string; components: Record<string, unknown> }[] }[];
  alerts: { alertId: number; ruleCode: string; severity: Severity; asOf: string; title: string; status: AlertStatus }[];
  sourceCodes: { source: string; source_code: string; source_name: string; match_method: string }[];
  disclaimer: string;
};

export type AlertRow = {
  alertId: number; ruleCode: string; ruleVersion: number; ruleName: string; severity: Severity; targetType: "COMPANY" | "REGION";
  targetKey: string; targetName?: string; asOf: string; title: string; status: AlertStatus; closeReason?: string;
  firstSeenAt: string; lastEvaluatedAt: string;
};
export type Evidence = {
  ruleCode: string; ruleVersion: number; severity: Severity; condition: string; params: Record<string, unknown>;
  message: string; observations: Record<string, unknown>[]; sources: Record<string, unknown>[]; calcRunId?: string;
};
export type AlertDetail = AlertRow & {
  ruleDescription: string; message: string; evidence: Evidence; ackedAt?: string; ackedBy?: string; closedAt?: string; calcRunId?: string; disclaimer: string;
};
export type RuleView = {
  ruleCode: string; version: number; targetType: string; nameKo: string; description: string; params: Record<string, unknown>;
  severity: Severity; enabled: boolean; condition: string; changeNote?: string; createdBy?: string; createdAt: string; openAlerts: number;
};
export type MetricDef = { code: string; target: string; nameKo: string; unit: string; formula: string; higherIsRisk: boolean; source: string };
export type Meta = {
  metrics: MetricDef[]; eventTypes: { code: string; name: string; keywords: string[] }[];
  sources: { code: string; name: string; use: string; url: string }[]; disclaimer: string;
};
// ---- 인증 · 감사 (ADR-013)
export type Role = "ADMIN" | "ANALYST";
export type Me = { authenticated: boolean; username?: string; roles: Role[]; authType?: "SESSION" | "TOKEN" };
export type AuditEntry = { auditId: number; at: string; actor: string; authType: string; action: string; target?: string; status?: number; detail?: Record<string, unknown>; ip?: string; traceId?: string };

// ---- 수주·보증 노출 · 주가 · 백테스트 (ADR-016 · 017)
export type ContractRow = {
  rceptNo: string; rceptDt: string; name: string; counterparty?: string; amount?: number; pctOfRevenue?: number; regionText?: string;
  regionCd?: string; regionName?: string; regionMatch: string; unsoldPer1kHh?: number; unsoldPeriod?: string; contractDate?: string;
  endDate?: string; correction: boolean; supersededBy?: string; terminatedBy?: string;
};
export type PfLine = { debtor?: string; provider?: string; pfType?: string; amount?: number };
export type GuaranteeRow = {
  rceptNo: string; rceptDt: string; debtor?: string; creditor?: string; amount?: number; equity?: number; pctOfEquity?: number;
  totalBalance?: number; balanceToEquityPct?: number; balanceIsLimit: boolean; usedBalance?: number; pfAmount?: number; pfLines: PfLine[]; endDate?: string; correction: boolean; supersededBy?: string;
};
export type CompanyFilings = { corpCode: string; contracts: ContractRow[]; guarantees: GuaranteeRow[]; parse: { parsed: number; partial: number; noDoc: number; failed: number }; disclaimer: string };
export type ExposureRow = {
  corpCode: string; corpName: string; contracts: number; totalAmount?: number; mappedAmount?: number; riskAmount?: number; riskSharePct?: number;
  riskContracts: number; latestBalanceToEquityPct?: number; latestGuaranteeDt?: string; pfAmount?: number; openAlerts: number;
};
export type Exposure = { days: number; unsoldPer1kHh: number; asOf: string; items: ExposureRow[]; method: string; disclaimer: string };
export type RegionContracts = { regionCd: string; totalAmount: number; companies: number; items: { rceptNo: string; rceptDt: string; corpCode: string; corpName: string; name: string; amount?: number; counterparty?: string; endDate?: string }[] };
export type Prices = { corpCode: string; stockCode?: string; bars: { d: string; c: number; cap?: number }[]; alerts: { alertId: number; ruleCode: string; date: string; title: string; severity: Severity }[]; source?: string };
export type BtStats = { events: number; used: number; excluded: Record<string, number>; meanExcess?: number; medianExcess?: number; negativeShare?: number; meanReturn?: number; tStat?: number };
export type Backtest = {
  horizon: number; rules: { ruleCode: string; ruleName: string; stats: BtStats }[]; baseline: BtStats;
  events: { alertId: number; ruleCode: string; corpCode: string; corpName: string; title: string; eventDate: string; entryDate?: string; exitDate?: string; ret?: number; bench?: number; excess?: number; benchSize?: number; excluded?: string }[];
  priceFrom?: string; priceTo?: string; stocks: number; method: string[]; limitations: string[]; disclaimer: string;
};

export type Row = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId?: string) { super(message); }
}

/** Spring Security 가 내려준 XSRF-TOKEN 쿠키 값 (SPA 가 읽어 헤더로 돌려보냄) */
function xsrf(): string | undefined {
  if (typeof document === "undefined") return undefined;
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : undefined;
}

const SAFE = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * 같은 출처 /api/v1 호출. 세션 쿠키(HttpOnly)는 브라우저가 붙이고, 변경 요청에는 CSRF 헤더를 붙입니다.
 * CSRF 토큰이 만료돼 403 CSRF_INVALID 가 오면 토큰을 새로 받아 한 번만 다시 시도합니다.
 */
export async function api<T>(path: string, init?: RequestInit, retried = false): Promise<T> {
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = { "Content-Type": "application/json", ...(init?.headers as Record<string, string> || {}) };
  if (!SAFE.has(method)) { const t = xsrf(); if (t) headers["X-XSRF-TOKEN"] = t; }
  const res = await fetch(`/api/v1${path}`, { ...init, method, headers, credentials: "same-origin" });
  const text = await res.text();
  let body: any = null; // eslint-disable-line @typescript-eslint/no-explicit-any
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  if (!res.ok) {
    if (res.status === 403 && body?.code === "CSRF_INVALID" && !retried) {
      await fetch("/api/v1/auth/me", { credentials: "same-origin" });
      return api<T>(path, init, true);
    }
    throw new ApiError(res.status, body?.code ?? "ERROR", body?.message ?? res.statusText, body?.traceId);
  }
  return body as T;
}

/** 변경 요청 (POST · PUT · PATCH · DELETE) — 로그인 세션 + CSRF */
export function mutate<T>(path: string, method: string, body?: unknown): Promise<T> {
  return api<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) });
}

export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== "") p.set(k, String(v)); });
  const s = p.toString();
  return s ? `?${s}` : "";
}
