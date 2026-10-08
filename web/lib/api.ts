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
export type RegionList = { metric: string; unit: string; period?: string; periods: string[]; items: RegionRow[]; calcRunId?: string; boundaryVersion: string; disclaimer: string };
/** 경계만 (지표 값 없음) — /regions/boundaries?v= 로 받아 브라우저가 영구 캐시 */
export type Boundaries = { version: string; type: "FeatureCollection"; features: { type: "Feature"; properties: Pick<RegionProps, "regionCd" | "name" | "fullName" | "sidoCd" | "sidoName" | "lat" | "lon">; geometry: Geometry }[] };
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
/** 근거 계보 (ADR-027) — 경보 → 규칙 버전 → 지표 → 표준계정 → 원천 줄(매핑 규칙) · 통계 · 공시 → 실행 */
export type LineageLine = { sjDiv: string; lineNo: number; accountId?: string; accountNm: string; amount?: number; mapId: number; matchType: string; pattern: string };
export type LineageStatement = {
  corpCode: string; periodKey: string; fsDiv: string; stdCode: string; stdName?: string; basis: "POINT" | "CUM" | "QTR"; amount?: number; rceptNo?: string;
  reprtCode?: string; dartUrl?: string; derivation?: string; reproduced: boolean; lines: LineageLine[]; derivedFrom: LineageStatement[];
  jobExecutionId?: number; fetchedAt?: string;
};
export type LineageMetric = { scope: "COMPANY" | "REGION"; targetKey: string; period: string; metricCode: string; name?: string; unit?: string; formula?: string; value?: number; status: string; components: Record<string, unknown>; calcRunId?: string };
export type LineageStat = { seriesId: string; name: string; unit: string; source: string; sourceTable: string; regionCd: string; period: string; value?: number; rawSymbol?: string; role: "OWN" | "CHILD"; collectRunId?: string; fetchedAt?: string };
export type LineageFiling = { rceptNo: string; kind: string; reportNm?: string; rceptDt?: string; eventType?: string; keyword?: string; dartUrl: string; parseStatus?: string; parserVersion?: number; facts: Record<string, unknown>; jobExecutionId?: number; fetchedAt?: string };
export type LineageRun = { kind: "RULE" | "METRIC" | "STANDARD" | "JOB" | "COLLECT"; id: string; name?: string; status?: string; startedAt?: string; finishedAt?: string };
export type Lineage = {
  alertId: number; targetType: string; targetKey: string; asOf: string;
  rule: { code: string; version: number; nameKo: string; condition?: string; params: Record<string, unknown>; changeNote?: string; createdAt?: string };
  metrics: LineageMetric[]; statements: LineageStatement[]; stats: LineageStat[]; filings: LineageFiling[]; runs: LineageRun[]; notes: string[]; disclaimer: string;
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
  pfPeriod?: string; pfGuaranteeToEquityPct?: number; pfDue12mToEquityPct?: number;
};
/** 기업 PF 우발채무 (ADR-024) — 금액은 원 단위, 회사 노출 = 단독 + 컨소시엄 회사분 */
export type PfDue = { m3?: number; m6?: number; m12?: number; y2?: number; y3?: number; over3?: number };
export type PfStatus = "PARSED" | "NO_STANDARD" | "NONE" | "NO_SECTION" | "NO_DOC";
export type PfReport = {
  rceptNo: string; periodKey: string; rceptDt: string; reportNm: string; status: PfStatus; message?: string;
  limit?: number; guarantee?: number; balance?: number; bridgeGuarantee?: number; bridgeBalance?: number; due?: PfDue; priorBalance?: number;
  sitesGuarantee?: number; reconciled?: boolean; statedTotal?: number; statedMatch?: boolean; truncated: boolean;
  equity?: number; equityFsDiv?: string; guaranteeToEquityPct?: number; due12mToEquityPct?: number; bridgeSharePct?: number; metricStatus?: string;
};
export type PfScope = "SOLE" | "CONSORTIUM_ALL" | "CONSORTIUM_SHARE";
export type PfSummaryRow = { scope: PfScope; category?: string; pfType?: string; total: boolean; limit?: number; guarantee?: number; balance?: number; due?: PfDue; priorBalance?: number };
export type PfSite = {
  seq: number; category?: string; regionText: string; regionCd?: string; regionName?: string; regionMatch: string; sidoName?: string;
  siteType?: string; pfType?: string; enhancement?: string; limitTotal?: number; sharePct?: number; guarantee: number; debtor?: string;
  related?: boolean; lenderType?: string; balance?: number; priorBalance?: number; loanPeriod?: string; maturity?: string; kind?: string;
  completionAmount?: number; unsoldPer1kHh?: number; unsoldPeriod?: string;
};
export type CompanyPf = {
  corpCode: string; current?: PfReport; history: PfReport[]; summary: PfSummaryRow[]; sites: PfSite[];
  coverage: { parsed: number; noStandard: number; none: number; noSection: number; noDoc: number }; method: string[]; disclaimer: string;
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

/** 경보 성적표 (ADR-023) — 재무 규칙 × 실제 부실 사건 */
export type ScInterval = { low: number; high: number };
export type ScRule = {
  ruleCode: string; episodes: number; hits: number; misses: number; pending: number; afterEvent: number;
  precision?: number; precisionCi?: ScInterval; lift?: number; events: number; caught: number; recall?: number; recallCi?: ScInterval;
  medianLeadDays?: number;
};
export type ScOutcome = "HIT" | "MISS" | "PENDING" | "AFTER_EVENT";
export type Scorecard = {
  horizonMonths: number; observedFrom?: string; observedTo: string; baseRate?: number; baseSamples: number;
  ruleNames: Record<string, string>; companyNames: Record<string, string>; rules: ScRule[];
  events: { corpCode: string; corpName: string; date: string; kind: string; rceptNo: string; reportNm: string }[];
  catches: { ruleCode: string; corpCode: string; eventDate: string; evaluable: boolean; caught: boolean; firstSignal?: string; leadDays?: number }[];
  episodes: { ruleCode: string; corpCode: string; start: string; last: string; signals: number; outcome: ScOutcome; eventDate?: string; leadDays?: number }[];
  method: string[]; limitations: string[]; disclaimer: string;
};

/** 사용자별 진입점 (/dashboard 의 personas) · 이름 찾기 (/search) */
export type Personas = {
  supplier: { corpCode: string; corpName: string; rules: string[]; sevRank: number }[];
  buyer: { corpCode: string; corpName: string; periodKey: string; due12mToEquityPct?: number; guaranteeToEquityPct?: number }[];
  investor?: { horizonMonths: number; observedFrom: string; observedTo: string; events: number; caught: number; medianLeadDays?: number; precision?: number; baseRate?: number; lift?: number };
};
export type SearchHit = { key: string; name: string; sub?: string };
export type SearchResult = { q: string; companies: SearchHit[]; regions: SearchHit[] };
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
/** 경로는 이 앱이 만든 '/…' 만 — 상위 경로(..)·프로토콜 상대(//)·역슬래시는 거부 (화면 파라미터가 섞여도 다른 API 로 새지 않게) */
function checkPath(path: string) {
  if (!path.startsWith("/") || path.startsWith("//") || path.includes("..") || path.includes("\\")) {
    throw new ApiError(400, "VALIDATION_ERROR", "잘못된 요청 경로입니다.");
  }
}

export async function api<T>(path: string, init?: RequestInit, retried = false): Promise<T> {
  checkPath(path);
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

/** 알림 (ADR-025) */
export type InboxItem = {
  inboxId: number; alertId: number; kind: "NEW" | "REOPENED"; createdAt: string; readAt?: string; ruleCode: string; severity: Severity;
  title: string; targetType: "COMPANY" | "REGION"; targetKey: string; targetName?: string; asOf: string; alertStatus: string;
};
export type Inbox = { items: InboxItem[]; unread: number; total: number; page: number; size: number };
export type Subscription = {
  subscriptionId: number; name: string; minSeverity: Severity; ruleCodes?: string[]; targetType?: "COMPANY" | "REGION"; watchOnly: boolean;
  channel: "IN_APP" | "WEBHOOK"; webhookId?: number; webhookName?: string; enabled: boolean; createdAt: string;
};
export type SubscriptionInput = Omit<Subscription, "subscriptionId" | "webhookName" | "createdAt">;
export type Watch = { targetType: "COMPANY" | "REGION"; targetKey: string; name?: string; createdAt: string };
export type Webhook = {
  webhookId: number; name: string; kind: "SLACK" | "GENERIC"; urlHint: string; signed: boolean; enabled: boolean; createdBy: string;
  createdAt: string; lastStatus?: string; lastSentAt?: string; pending: number; dead: number; subscriptions: number;
};
export type WebhookList = { available: boolean; items: Webhook[] };
export type WebhookDelivery = { deliveryId: number; alertId: number; title: string; kind: string; status: string; attempts: number; lastError?: string; createdAt: string; nextAt: string; sentAt?: string };

/** 사업장 카드 (ADR-026) */
export type SiteMatch = "JIBUN" | "UMD" | "UMD_NO_TRADE" | "MULTI" | "NONE";
export type UmdMarket = {
  fromYm: number; toYm: number; trades: number; tradesPrev: number; tradesYoyPct?: number; cancels: number; cancelRatePct?: number;
  medianPriceM2?: number; medianPriceM2Prev?: number; priceYoyPct?: number;
};
export type SiteCard = {
  rceptNo: string; rceptDt: string; corpCode: string; corpName: string; contractName: string; amount?: number; endDate?: string;
  regionCd: string; regionName?: string; regionText: string; unsoldPer1kHh?: number; unsoldPeriod?: string; match: SiteMatch;
  umd?: string; umdAggregate: boolean; jibun?: string; market?: UmdMarket;
  complex?: { aptNm?: string; trades: number; medianPriceM2?: number; lastDealDate?: string; buildYear?: number };
};
export type Sites = {
  items: SiteCard[]; coverage: { total: number; jibun: number; umd: number; noTrade: number; multi: number; none: number };
  method: string[]; disclaimer: string;
};
