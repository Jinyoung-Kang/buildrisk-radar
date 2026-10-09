// 공시 원문 구조화 결과(수주 · 채무보증 · PF)를 읽는 규칙
import type { ContractRow, PfDue } from "@/lib/api";

/** 수주 계약의 지금 상태 — 해지 > 정정됨(뒤 정정 공시가 대체) > 현재(정정 공시 자체면 CORRECTED) */
export type ContractState = "TERMINATED" | "SUPERSEDED" | "CORRECTED" | "CURRENT";
export function contractState(c: ContractRow): ContractState {
  if (c.terminatedBy) return "TERMINATED";
  if (c.supersededBy) return "SUPERSEDED";
  return c.correction ? "CORRECTED" : "CURRENT";
}

/** R-C06 기본값 — 화면의 빨강 표시 기준 (규칙 파라미터를 바꾸면 경보 기준은 달라짐): PF 보증 / 자기자본 · 12개월 내 만기 / 자기자본 (%) */
export const PF_GUARANTEE_TH = 100, PF_DUE12_TH = 50;

export const PF_BUCKETS: { key: keyof PfDue; label: string; within: boolean }[] = [
  { key: "m3", label: "3개월 이내", within: true }, { key: "m6", label: "3~6개월", within: true }, { key: "m12", label: "6~12개월", within: true },
  { key: "y2", label: "1~2년", within: false }, { key: "y3", label: "2~3년", within: false }, { key: "over3", label: "3년 초과", within: false },
];

/** 12개월 안에 만기가 오는 PF 합 — 구간 값이 하나도 없으면 모름(undefined), 있으면 빈 구간은 0 */
export function due12(d?: PfDue): number | undefined {
  return d && PF_BUCKETS.some((b) => d[b.key] != null) ? (d.m3 ?? 0) + (d.m6 ?? 0) + (d.m12 ?? 0) : undefined;
}
