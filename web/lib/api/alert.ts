// 경보 · 규칙 — 조회 · 상태 변경 · 규칙 편집 · CSV 주소 — 화면은 `import * as alertApi from "@/lib/api/alert"` (ADR-030)
import { mutate, qs, query, BASE } from "./client";
import type { AlertDetail, AlertRow, Lineage, Page, RuleView } from "./types";

const e = encodeURIComponent;
export const list = (p: { status?: string; severity?: string; targetType?: string; page: number; size: number }) => query<Page<AlertRow>>(`/alerts${qs(p)}`);
export const detail = (id: number | string) => query<AlertDetail>(`/alerts/${e(String(id))}`);
export const lineage = (id: number) => query<Lineage>(`/alerts/${id}/lineage`);
export const rules = () => query<RuleView[]>("/rules");
export const ruleHistory = (code: string) => query<{ ruleCode: string; versions: RuleView[] }>(`/rules/${e(code)}`);
export const setStatus = (id: number | string, status: "ACK" | "OPEN") => mutate<AlertDetail>(`/alerts/${e(String(id))}`, "PATCH", { status });
export const updateRule = (code: string, body: { params: Record<string, unknown>; severity: string; enabled: boolean; changeNote: string }) =>
  mutate<RuleView>(`/rules/${e(code)}`, "PUT", body);
export const csv = (p: { status?: string; severity?: string; targetType?: string }) => `${BASE}/export/alerts.csv${qs(p)}`;
