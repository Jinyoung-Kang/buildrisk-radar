// 공시 원문 구조화 결과(수주 · 채무보증 · PF)를 읽는 규칙
import type { ContractRow, PfDue, RiskShare } from "@/lib/api";

/** 수주 계약의 지금 상태 — 해지 > 정정됨(뒤 정정 공시가 대체) > 현재(정정 공시 자체면 CORRECTED) */
export type ContractState = "TERMINATED" | "SUPERSEDED" | "CORRECTED" | "CURRENT";
export function contractState(c: ContractRow): ContractState {
  if (c.terminatedBy) return "TERMINATED";
  if (c.supersededBy) return "SUPERSEDED";
  return c.correction ? "CORRECTED" : "CURRENT";
}

/**
 * 수주 · 보증 탭의 '위험 지역 수주 비중' 카드 — 숫자는 서버가 경보 R-X01 과 같은 계산 · 기간 · 기준으로 내려 준 값만 씀.
 * 화면이 모든 현재 계약을 기간 없이 기준 5 로 따로 계산해 같은 화면의 경보와 숫자가 달랐음 (VERIFICATION 187). th 는 표의 강조 기준
 */
export function riskShareCard(rs: RiskShare | null | undefined, fallbackTh: number): { th: number; title: string; sharePct: number | null; sub: string } {
  const th = rs?.unsoldPer1kHh ?? fallbackTh;
  return {
    th,
    title: `미분양 ${th}호/천가구 이상 지역 비중`,
    sharePct: rs?.sharePct ?? null,
    sub: rs ? `최근 ${rs.windowDays}일 · 시군구로 매핑된 ${rs.contracts}건 · 경보 R-X01 과 같은 기준` : "경보 R-X01 기준을 받지 못함",
  };
}

/** R-C06 기본값 — 화면의 빨강 표시 기준 (규칙 파라미터를 바꾸면 경보 기준은 달라짐): PF 보증 / 자기자본 · 12개월 내 만기 / 자기자본 (%) */
export const PF_GUARANTEE_TH = 100, PF_DUE12_TH = 50;

export const PF_BUCKETS: { key: keyof PfDue; label: string; within: boolean }[] = [
  { key: "m3", label: "3개월 이내", within: true }, { key: "m6", label: "3~6개월", within: true }, { key: "m12", label: "6~12개월", within: true },
  { key: "y2", label: "1~2년", within: false }, { key: "y3", label: "2~3년", within: false }, { key: "over3", label: "3년 초과", within: false },
];

/** 12개월 안에 만기가 오는 PF 합 — 서버가 원문 구간에서 센 합(within12m)이 우선. 없으면 구간 값이 하나도 없을 때 모름(undefined), 있으면 빈 구간은 0 */
export function due12(d?: PfDue): number | undefined {
  if (d?.within12m != null) return d.within12m;
  return d && PF_BUCKETS.some((b) => d[b.key] != null) ? (d.m3 ?? 0) + (d.m6 ?? 0) + (d.m12 ?? 0) : undefined;
}

/** 원문 구간이 표준 칸으로 다 나뉘지 않음 — 12개월 안 합만 알고 칸별 값은 일부 비어 있음 */
export function duePartial(d?: PfDue): boolean {
  return d?.within12m != null && d.within12m !== (d.m3 ?? 0) + (d.m6 ?? 0) + (d.m12 ?? 0);
}
