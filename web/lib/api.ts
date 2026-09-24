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
  ruleDescription: string; message: string; evidence: Evidence; ackedAt?: string; closedAt?: string; calcRunId?: string; disclaimer: string;
};
export type RuleView = {
  ruleCode: string; version: number; targetType: string; nameKo: string; description: string; params: Record<string, unknown>;
  severity: Severity; enabled: boolean; condition: string; changeNote?: string; createdAt: string; openAlerts: number;
};
export type MetricDef = { code: string; target: string; nameKo: string; unit: string; formula: string; higherIsRisk: boolean; source: string };
export type Meta = {
  metrics: MetricDef[]; eventTypes: { code: string; name: string; keywords: string[] }[];
  sources: { code: string; name: string; use: string; url: string }[]; disclaimer: string;
};
export type Row = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId?: string) { super(message); }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api/v1${path}`, { ...init, headers: { "Content-Type": "application/json", ...(init?.headers || {}) } });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, body?.code ?? "ERROR", body?.message ?? res.statusText, body?.traceId);
  return body as T;
}

export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== "") p.set(k, String(v)); });
  const s = p.toString();
  return s ? `?${s}` : "";
}

/** 관리 토큰은 이 브라우저 세션에만 보관합니다 (.env 의 ADMIN_TOKEN) */
export const adminToken = {
  get: () => { try { return sessionStorage.getItem("br-admin-token") ?? ""; } catch { return ""; } },
  set: (t: string) => { try { sessionStorage.setItem("br-admin-token", t); } catch { /* 저장 불가 환경 */ } },
};

export function adminApi<T>(path: string, method: string, body?: unknown): Promise<T> {
  return api<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body), headers: { "X-Admin-Token": adminToken.get() } });
}
