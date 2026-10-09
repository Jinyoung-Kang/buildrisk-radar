// 운영 — 배치 · 감사 · 매핑 (관리 화면) — 화면은 `import * as opsApi from "@/lib/api/ops"` (ADR-030)
import { mutate, qs, query } from "./client";
import type { AuditEntry, Row } from "./types";

const e = encodeURIComponent;
export const audit = (p: Record<string, string | number | undefined>) => query<{ items: AuditEntry[] }>(`/admin/audit${qs(p)}`);
export const batchJobs = () => query<Row>("/batch/jobs");
export const batchRequests = () => query<Row[]>("/batch/requests?limit=15");
export const batchExecutions = (jobName?: string) => query<Row[]>(`/batch/executions${qs({ jobName, limit: 60 })}`);
export const batchExecution = (id: number | string) => query<Row>(`/batch/executions/${e(String(id))}`);
export const launchJob = (name: string, fresh: boolean) => mutate<Row>(`/batch/jobs/${e(name)}/launch`, "POST", fresh ? { restart: false } : {});
export const recoverExecution = (execId: number) => mutate<Row>(`/batch/executions/${e(String(execId))}/recover`, "POST");
export const unmapped = () => query<Row>("/mapping/unmapped");
export const accountRules = () => query<Row[]>("/mapping/account-rules");
export const addAccountRule = (body: { stdCode: string; sjDiv: string; matchType: string; pattern: string; priority: number }) =>
  mutate<Row>("/mapping/account-rules", "POST", body);
export const deleteAccountRule = (id: number | string) => mutate<Row>(`/mapping/account-rules/${e(String(id))}`, "DELETE");
export const mapRegionCode = (body: { source: string; sourceCode: string; regionCd: string | null; note: string }) =>
  mutate<Row>("/mapping/region-codes", "PUT", body);
