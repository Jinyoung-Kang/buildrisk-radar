// 기업 상세 — 지표 · 재무 · 공시 화면용 변환
import type { Disclosure, Financials, Metrics } from "@/lib/api";
import { pk } from "./format";

/** 위험 공시 유형 (회생 · 부도 · 거래정지 · 감사의견) */
export const RISK_EVENTS = new Set(["REHAB", "DEFAULT", "SUSPENSION", "AUDIT_OPINION"]);

/** 지표 코드 → 차트 점 (분기 표기 26.2Q, 값 없음은 빈 점) */
export function metricSeries(metrics: Metrics | null | undefined): Record<string, { x: string; v?: number }[]> {
  const by: Record<string, { x: string; v?: number }[]> = {};
  metrics?.metrics.forEach((m) => { by[m.code] = m.points.map((p) => ({ x: pk(p.periodKey), v: p.value ?? undefined })); });
  return by;
}

/** 재무 표에 보일 최근 기간 (오래된 → 최근) */
export function recentPeriods(fin: Financials | null | undefined, n = 8): string[] {
  return Array.from(new Set(fin?.series.flatMap((x) => x.points.map((p) => p.periodKey)) ?? [])).sort().slice(-n);
}

export function filterDisclosures(items: Disclosure[], filter: "risk" | "all"): Disclosure[] {
  return items.filter((d) => filter === "all" || RISK_EVENTS.has(d.eventType));
}

/** 종목 표시 — 지금 상장이면 '종목코드 · 유가/코스닥', 아니면 '비상장'(상장폐지로 종목코드가 남은 회사 포함 — 주가 · 백테스트 대상 아님) */
export function listingLabel(c: { stockCode?: string; corpCls?: string; listed: boolean }): string {
  if (!c.listed || !c.stockCode) return "비상장";
  return `${c.stockCode} · ${c.corpCls === "Y" ? "유가" : c.corpCls === "K" ? "코스닥" : c.corpCls ?? ""}`;
}

/** 대시보드 문장의 유니버스 — 비상장이 있으면 나눠 보임 */
export function universeLabel(total: number, listed?: number | null): string {
  if (listed == null || listed >= total) return `건설사 ${total}곳(상장)`;
  return `건설사 ${total}곳(상장 ${listed} · 비상장 ${total - listed})`;
}
