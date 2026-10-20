// 백엔드(Spring Boot) API 응답 타입 — 호출은 client · endpoints

export type Severity = "HIGH" | "MEDIUM" | "LOW";
export type AlertStatus = "OPEN" | "ACK" | "CLOSED";
export type Page<T> = { items: T[]; total: number; page: number; size: number };

export type CompanyRow = {
  /** listed: 지금 상장(유가 · 코스닥) — 비상장 · 상장폐지는 종목코드가 남아 있어도 false */
  corpCode: string; corpName: string; stockCode?: string; corpCls?: string; listed: boolean; indutyCode?: string;
  latestPeriod?: string; debtRatio?: number; debtRatioStatus?: string; interestCoverage?: number;
  interestCoverageStatus?: string; borrowingDep?: number; openAlerts: number; maxSeverity?: Severity;
  lastDisclosureDate?: string; lastDisclosure?: string;
};
export type MetricBrief = { code: string; nameKo: string; value?: number; unit: string; status: string; yoyPp?: number; formula: string };
export type AlertBrief = { alertId: number; ruleCode: string; ruleVersion?: number; severity: Severity; asOf: string; title: string; status: AlertStatus };
export type CompanySummary = {
  corpCode: string; corpName: string; stockCode?: string; corpCls?: string; listed: boolean; jurirNo?: string; bizrNo?: string;
  indutyCode?: string; address?: string;
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
/** R-X02 의 청약홈 분양 공고 (ADR-032) — 시공사 대조 결과 */
export type LineagePresale = {
  houseManageNo: string; pblancNo: string; houseNm: string; noticeDate: string; address?: string; regionCd?: string; regionMatch?: string;
  units?: number; moveinYm?: string; builderRaw?: string; pblancUrl?: string; removed: boolean; linkVersion?: number; fetchedAt?: string;
  builders: { name: string; corpCode?: string; matchStatus: string; matchMethod?: string; candidates?: string[] | null }[];
};
export type LineageRun = { kind: "RULE" | "METRIC" | "STANDARD" | "JOB" | "COLLECT"; id: string; name?: string; status?: string; startedAt?: string; finishedAt?: string };
export type Lineage = {
  alertId: number; targetType: string; targetKey: string; asOf: string;
  rule: { code: string; version: number; nameKo: string; condition?: string; params: Record<string, unknown>; changeNote?: string; createdAt?: string };
  metrics: LineageMetric[]; statements: LineageStatement[]; stats: LineageStat[]; filings: LineageFiling[]; presales?: LineagePresale[];
  runs: LineageRun[]; notes: string[]; disclaimer: string;
};
export type AlertDetail = AlertRow & {
  ruleDescription: string; message: string; evidence: Evidence; ackedAt?: string; ackedBy?: string; closedAt?: string; calcRunId?: string;
  calcJobExecutionId?: number; calcRunAt?: string; disclaimer: string;
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
/** 화면 설정 /api/config — Next 서버가 런타임 환경변수로 만듦(백엔드 아님). demo = 공개 데모(로그인 불가, ADR-031) */
export type SiteConfig = { kakaoJsKey: string; demo: boolean };
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
/** 위험 지역 수주 비중 — 경보 R-X01 과 같은 계산 · 최신 파라미터 (VERIFICATION 187). 기간 안에 셀 계약이 없으면 sharePct 없음 */
export type RiskShare = { windowDays: number; unsoldPer1kHh: number; ruleVersion: number; contracts: number; totalAmount: number; riskAmount: number; sharePct?: number | null };
export type CompanyFilings = { corpCode: string; contracts: ContractRow[]; guarantees: GuaranteeRow[]; parse: { parsed: number; partial: number; noDoc: number; failed: number }; riskShare?: RiskShare | null; disclaimer: string };
export type ExposureRow = {
  corpCode: string; corpName: string; contracts: number; totalAmount?: number; mappedAmount?: number; riskAmount?: number; riskSharePct?: number;
  riskContracts: number; latestBalanceToEquityPct?: number; latestGuaranteeDt?: string; pfAmount?: number; openAlerts: number;
  pfPeriod?: string; pfGuaranteeToEquityPct?: number; pfDue12mToEquityPct?: number;
  /** 청약홈 분양 공고 (ADR-032) — 같은 기간 · 기준, 세대 = 공급규모 */
  presaleNotices: number; presaleUnits?: number; presaleRiskUnits?: number; presaleRiskSharePct?: number; remainderUnits?: number;
  /** pfPeriod 분기 PF 지표의 상태 — OK 가 아니면 비율은 비어 있음(NEG_EQUITY = 자본잠식) */
  pfStatus?: string;
};
/** 청약홈 분양 공고 (ADR-032) — 시공사 한 곳. matchStatus: 기업 대조 결과 */
export type PresaleBuilder = { name: string; corpCode?: string; corpName?: string; matchStatus: "MATCHED" | "AMBIGUOUS" | "UNMATCHED"; target: boolean };
export type PresaleRow = {
  houseManageNo: string; pblancNo: string; houseNm: string; houseSecd: string; noticeDate: string; regionText?: string; regionCd?: string;
  regionName?: string; regionMatch?: string; units?: number; moveinYm?: string; flags: string[]; builderRaw?: string;
  consortium: number; consortiumMore: boolean; partners: PresaleBuilder[]; remainderNotices: number; remainderUnits?: number;
  unsoldPer1kHh?: number; unsoldPeriod?: string; pblancUrl?: string; removed: boolean;
};
export type PresaleShare = { windowDays: number; unsoldPer1kHh: number; ruleVersion: number; notices: number; totalUnits: number; riskUnits: number; sharePct?: number | null; excluded: number };
export type CompanyPresales = { corpCode: string; items: PresaleRow[]; share: PresaleShare; method: string; disclaimer: string };
export type RegionPresales = {
  regionCd: string;
  items: { houseManageNo: string; pblancNo: string; houseNm: string; noticeDate: string; units?: number; moveinYm?: string; builders: PresaleBuilder[]; pblancUrl?: string }[];
  upcoming: { units: number; complexes: number; fromYm: string; toYm: string };
  remainder: { notices: number; units: number; since: string };
  companies: { corpCode: string; corpName: string; notices: number; units: number }[];
  method: string; disclaimer: string;
};
/** 기업 PF 우발채무 (ADR-024) — 금액은 원 단위, 회사 노출 = 단독 + 컨소시엄 회사분 */
/** within12m = 12개월 안 합 — 원문 구간이 표준 칸과 다르면(예: '6개월이내') 칸은 비고 합만 있음 */
export type PfDue = { m3?: number; m6?: number; m12?: number; y2?: number; y3?: number; over3?: number; within12m?: number };
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
  /** 가장 최근 정기보고서가 자본잠식이라 비율을 셀 수 없는 시공사 */
  buyerNegEquity?: { corpCode: string; corpName: string; periodKey: string }[];
  investor?: { horizonMonths: number; observedFrom: string; observedTo: string; events: number; caught: number; medianLeadDays?: number; precision?: number; baseRate?: number; lift?: number };
};
export type SearchHit = { key: string; name: string; sub?: string };
export type SearchResult = { q: string; companies: SearchHit[]; regions: SearchHit[] };
export type Row = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

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
