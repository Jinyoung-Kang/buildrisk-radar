// 청약홈 분양 공고(ADR-032)를 읽는 규칙 — 숫자는 서버가 낸 값만, 화면은 문구 · 고르기만
import type { PresaleRow, PresaleShare } from "@/lib/api";

/**
 * '분양' 탭의 위험 지역 분양 비중 카드 — 서버가 경보 R-X02 와 같은 계산 · 기간 · 기준으로 낸 값만 씀
 * (수주 비중 카드 riskShareCard 와 같은 원칙 — VERIFICATION 187). th 는 표의 강조 기준
 */
export function presaleShareCard(s: PresaleShare | null | undefined, fallbackTh: number): { th: number; title: string; sharePct: number | null; sub: string } {
  const th = s?.unsoldPer1kHh ?? fallbackTh;
  return {
    th,
    title: `미분양 ${th}호/천가구 이상 지역 분양 비중`,
    sharePct: s?.sharePct ?? null,
    sub: s ? `최근 ${s.windowDays}일 · 시군구 공고 ${s.notices}건 ${s.totalUnits.toLocaleString("ko-KR")}세대${s.excluded ? ` · 미분양 지표 없는 지역 ${s.excluded}건 제외` : ""} · 경보 R-X02 와 같은 기준`
      : "경보 R-X02 기준을 받지 못함",
  };
}

/** 컨소시엄 — 지분이 공고에 없어 세대를 나누지 않고 각 시공사에 그대로 셈. 단독이면 null */
export function consortiumLabel(r: Pick<PresaleRow, "consortium" | "consortiumMore">): string | null {
  if (r.consortium <= 1 && !r.consortiumMore) return null;
  return `컨소시엄 ${r.consortium}사${r.consortiumMore ? " 이상" : ""} (지분 미공개)`;
}

/** 입주예정월 'YYYYMM' → 'YYYY.MM' (형식이 다르면 –) */
export function moveinLabel(ym?: string | null): string {
  return ym && /^\d{6}$/.test(ym) ? `${ym.slice(0, 4)}.${ym.slice(4)}` : "–";
}

/** 이 공고에 이어진 잔여세대(무순위 · 재공급) 공고 — 없으면 null */
export function remainderLabel(r: Pick<PresaleRow, "remainderNotices" | "remainderUnits">): string | null {
  if (r.remainderNotices <= 0) return null;
  return `잔여세대 공고 ${r.remainderNotices}건${r.remainderUnits != null ? ` · ${r.remainderUnits.toLocaleString("ko-KR")}세대` : ""}`;
}

const HOUSE_KIND: Record<string, string> = { "01": "APT", "09": "민간사전청약", "10": "신혼희망타운" };
export const houseKind = (code: string): string => HOUSE_KIND[code] ?? code;

/** 사라진(정정 · 취소) 공고를 뺀 목록과 그 세대 합 */
export function currentPresales(items: PresaleRow[]): { rows: PresaleRow[]; units: number } {
  const rows = items.filter((r) => !r.removed);
  return { rows, units: rows.reduce((a, r) => a + (r.units ?? 0), 0) };
}

/** 기준월(now, 'YYYYMM') 다음 달 ~ 12개월 뒤 입주예정인 공고 — 세대 합 · 단지 수 (청약홈 공고 단지만이라 하한) */
export function upcomingMovein(items: PresaleRow[], nowYm: string): { units: number; complexes: number; fromYm: string; toYm: string } {
  const y = Number(nowYm.slice(0, 4)), m = Number(nowYm.slice(4));
  const shift = (k: number) => { const t = y * 12 + (m - 1) + k; return `${Math.floor(t / 12)}${String((t % 12) + 1).padStart(2, "0")}`; };
  const fromYm = shift(1), toYm = shift(12);
  const rows = items.filter((r) => !r.removed && r.moveinYm != null && r.moveinYm >= fromYm && r.moveinYm <= toYm);
  return { units: rows.reduce((a, r) => a + (r.units ?? 0), 0), complexes: rows.length, fromYm, toYm };
}
