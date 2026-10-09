import type { ExposureRow } from "@/lib/api";

export type ExposureSort = "risk" | "guarantee" | "pf" | "amount" | "name";

/** 노출 표 정렬 — 비율은 큰 순(값 없음은 맨 뒤), 이름은 가나다 */
const BY: Record<ExposureSort, (a: ExposureRow, b: ExposureRow) => number> = {
  risk: (a, b) => (b.riskSharePct ?? -1) - (a.riskSharePct ?? -1),
  guarantee: (a, b) => (b.latestBalanceToEquityPct ?? -1) - (a.latestBalanceToEquityPct ?? -1),
  pf: (a, b) => (b.pfGuaranteeToEquityPct ?? -1) - (a.pfGuaranteeToEquityPct ?? -1),
  amount: (a, b) => (b.totalAmount ?? 0) - (a.totalAmount ?? 0),
  name: (a, b) => a.corpName.localeCompare(b.corpName, "ko"),
};

export function sortExposure(rows: ExposureRow[], sort: ExposureSort): ExposureRow[] {
  return [...rows].sort(BY[sort]);
}
