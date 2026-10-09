// /api/v1 의 경로 · 메서드 · 응답 타입을 한 곳에 — 화면은 경로를 만들지 않고 이 함수를 부릅니다 (ADR-030)
import { api, BASE, mutate, qs, query } from "./client";
import type {
  AlertDetail, AlertRow, AuditEntry, Backtest, Boundaries, CompanyFilings, CompanyPf, CompanyRow, CompanySummary, Disclosure,
  Exposure, Financials, Inbox, Lineage, Me, Meta, Metrics, Page, Prices, RegionContracts, RegionList, RegionSeries, Row, RuleView,
  Scorecard, SearchResult, Sites, Subscription, SubscriptionInput, Watch, Webhook, WebhookDelivery, WebhookList,
} from "./types";

type TargetType = "COMPANY" | "REGION";
const e = encodeURIComponent;

/** 조회 (GET) — useApi 에 넘김 */
export const queries = {
  dashboard: () => query<Row>("/dashboard"),
  meta: () => query<Meta>("/meta"),
  search: (q: string) => query<SearchResult>(`/search${qs({ q })}`),
  exposure: (days: number | string, unsoldPer1kHh: string) => query<Exposure>(`/exposure${qs({ days, unsoldPer1kHh })}`),
  backtest: (horizon: number | string) => query<Backtest>(`/backtest?horizon=${horizon}`),
  scorecard: (horizonMonths: number | string) => query<Scorecard>(`/scorecard?horizonMonths=${horizonMonths}`),

  companies: (p: { q?: string; sort?: string; page: number; size: number }) => query<Page<CompanyRow>>(`/companies${qs(p)}`),
  company: (corp: string) => query<CompanySummary>(`/companies/${e(corp)}`),
  companyMetrics: (corp: string) => query<Metrics>(`/companies/${e(corp)}/metrics`),
  companyFinancials: (corp: string, fsDiv?: string) => query<Financials>(`/companies/${e(corp)}/financials${qs({ fsDiv })}`),
  companyDisclosures: (corp: string) => query<Disclosure[]>(`/companies/${e(corp)}/disclosures`),
  companyFilings: (corp: string) => query<CompanyFilings>(`/companies/${e(corp)}/filings`),
  companyPf: (corp: string) => query<CompanyPf>(`/companies/${e(corp)}/pf`),
  companyPrices: (corp: string) => query<Prices>(`/companies/${e(corp)}/prices?days=1095`),
  companySites: (corp: string) => query<Sites>(`/companies/${e(corp)}/sites`),

  regions: (p: { metric?: string; period?: string; sido?: string }) => query<RegionList>(`/regions${qs(p)}`),
  regionBoundaries: (version: string) => query<Boundaries>(`/regions/boundaries${qs({ v: version })}`),
  regionSeries: (cd: string) => query<RegionSeries>(`/regions/${e(cd)}/series`),
  regionContracts: (cd: string) => query<RegionContracts>(`/regions/${e(cd)}/contracts`),
  regionSites: (cd: string) => query<Sites>(`/regions/${e(cd)}/sites`),

  alerts: (p: { status?: string; severity?: string; targetType?: string; page: number; size: number }) => query<Page<AlertRow>>(`/alerts${qs(p)}`),
  alert: (id: number | string) => query<AlertDetail>(`/alerts/${e(String(id))}`),
  alertLineage: (id: number) => query<Lineage>(`/alerts/${id}/lineage`),
  rules: () => query<RuleView[]>("/rules"),
  ruleHistory: (code: string) => query<{ ruleCode: string; versions: RuleView[] }>(`/rules/${e(code)}`),

  inbox: (p: { unreadOnly: boolean; page: number; size: number }) => query<Inbox>(`/me/notifications${qs(p)}`),
  subscriptions: () => query<Subscription[]>("/me/subscriptions"),
  watches: () => query<Watch[]>("/me/watch"),
  webhooks: () => query<WebhookList>("/admin/webhooks"),
  webhookDeliveries: (id: number) => query<WebhookDelivery[]>(`/admin/webhooks/${id}/deliveries`),
  audit: (p: Record<string, string | number | undefined>) => query<{ items: AuditEntry[] }>(`/admin/audit${qs(p)}`),

  batchJobs: () => query<Row>("/batch/jobs"),
  batchRequests: () => query<Row[]>("/batch/requests?limit=15"),
  batchExecutions: (jobName?: string) => query<Row[]>(`/batch/executions${qs({ jobName, limit: 60 })}`),
  batchExecution: (id: number | string) => query<Row>(`/batch/executions/${e(String(id))}`),
  mappingUnmapped: () => query<Row>("/mapping/unmapped"),
  mappingAccountRules: () => query<Row[]>("/mapping/account-rules"),
};

/** CSV 내려받기 주소 (a href — 브라우저가 쿠키와 함께 받음) */
export const downloads = {
  alertsCsv: (p: { status?: string; severity?: string; targetType?: string }) => `${BASE}/export/alerts.csv${qs(p)}`,
  exposureCsv: (days: number | string, unsoldPer1kHh: string) => `${BASE}/export/exposure.csv?days=${days}&unsoldPer1kHh=${unsoldPer1kHh}`,
  companyMetricsCsv: (corp: string) => `${BASE}/export/companies/${e(corp)}/metrics.csv`,
  pfSitesCsv: (corp: string) => `${BASE}/export/companies/${e(corp)}/pf-sites.csv`,
};

/** 구독 저장 본문 — 비운 값은 null 로 (서버가 '조건 없음'으로 읽음) */
export type SubscriptionBody = Omit<SubscriptionInput, "targetType" | "ruleCodes" | "webhookId"> & {
  targetType: TargetType | null; ruleCodes: string[] | null; webhookId: number | null | undefined;
};

/** 변경 (POST · PUT · PATCH · DELETE) — 로그인 세션 + CSRF */
export const commands = {
  login: (username: string, password: string) => mutate<Me>("/auth/login", "POST", { username, password }),
  logout: () => mutate<void>("/auth/logout", "POST"),
  me: () => api<Me>("/auth/me"),

  setAlertStatus: (id: number | string, status: "ACK" | "OPEN") => mutate<AlertDetail>(`/alerts/${e(String(id))}`, "PATCH", { status }),
  updateRule: (code: string, body: { params: Record<string, unknown>; severity: string; enabled: boolean; changeNote: string }) =>
    mutate<RuleView>(`/rules/${e(code)}`, "PUT", body),

  unreadCount: () => api<{ unread: number }>("/me/notifications/unread-count"),
  markRead: (body: { ids?: number[]; all?: boolean }) => mutate<number>("/me/notifications/read", "POST", body),
  createSubscription: (body: SubscriptionBody | Subscription) => mutate<Subscription>("/me/subscriptions", "POST", body),
  updateSubscription: (id: number, body: SubscriptionBody | Subscription) => mutate<Subscription>(`/me/subscriptions/${id}`, "PUT", body),
  deleteSubscription: (id: number) => mutate<void>(`/me/subscriptions/${id}`, "DELETE"),
  watch: (type: TargetType, key: string) => mutate<void>(`/me/watch/${type}/${e(key)}`, "PUT"),
  unwatch: (type: TargetType, key: string) => mutate<void>(`/me/watch/${type}/${e(key)}`, "DELETE"),
  createWebhook: (body: { name: string; kind: "SLACK" | "GENERIC"; url: string; secret: string | null }) =>
    mutate<{ webhook: Webhook; secret?: string }>("/admin/webhooks", "POST", body),
  testWebhook: (id: number) => mutate<{ ok: boolean; status: number; error?: string }>(`/admin/webhooks/${id}/test`, "POST"),
  setWebhookEnabled: (id: number, enabled: boolean) => mutate<void>(`/admin/webhooks/${id}`, "PATCH", { enabled }),
  deleteWebhook: (id: number) => mutate<void>(`/admin/webhooks/${id}`, "DELETE"),

  launchJob: (name: string, fresh: boolean) => mutate<Row>(`/batch/jobs/${e(name)}/launch`, "POST", fresh ? { restart: false } : {}),
  recoverExecution: (execId: number) => mutate<Row>(`/batch/executions/${e(String(execId))}/recover`, "POST"),
  addAccountRule: (body: { stdCode: string; sjDiv: string; matchType: string; pattern: string; priority: number }) =>
    mutate<Row>("/mapping/account-rules", "POST", body),
  deleteAccountRule: (id: number | string) => mutate<Row>(`/mapping/account-rules/${e(String(id))}`, "DELETE"),
  mapRegionCode: (body: { source: string; sourceCode: string; regionCd: string | null; note: string }) =>
    mutate<Row>("/mapping/region-codes", "PUT", body),
};
