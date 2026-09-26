const nf = new Intl.NumberFormat("ko-KR");

export function num(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || Number.isNaN(v)) return "–";
  const abs = Math.abs(v);
  const d = abs >= 1000 ? 0 : abs >= 100 ? Math.min(digits, 1) : digits;
  return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: d, minimumFractionDigits: 0 }).format(v);
}

export function int(v: number | null | undefined): string {
  return v === null || v === undefined ? "–" : nf.format(Math.round(v));
}

/** 원 → 억 원 */
export function eok(won: number | null | undefined): string {
  if (won === null || won === undefined) return "–";
  const e = won / 1e8;
  return `${new Intl.NumberFormat("ko-KR", { maximumFractionDigits: Math.abs(e) >= 100 ? 0 : 1 }).format(e)}억`;
}

export function withUnit(v: number | null | undefined, unit: string): string {
  if (v === null || v === undefined) return "–";
  if (unit === "원") return `${eok(v)} 원`;
  if (unit === "%" || unit === "%p") return `${num(v)}${unit}`;
  if (unit === "배") return `${num(v, 2)}배`;
  if (unit === "pt") return `${v > 0 ? "+" : ""}${num(v, 2)}pt`;
  return `${num(v)} ${unit}`;
}

export function ym(p?: string | null): string {
  if (!p) return "–";
  if (/^\d{6}$/.test(p)) return `${p.slice(0, 4)}.${p.slice(4)}`;
  return p;
}

/** 2026Q2 → 26.2Q */
export function pk(p?: string | null): string {
  if (!p) return "–";
  const m = /^(\d{4})Q([1-4])$/.exec(p);
  return m ? `${m[1].slice(2)}.${m[2]}Q` : p;
}

export function dt(s?: string | null): string {
  if (!s) return "–";
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return s;
  return d.toLocaleString("ko-KR", { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false });
}

export const STATUS_LABEL: Record<string, string> = {
  OK: "정상", MISSING: "계정·통계 없음", NEG_EQUITY: "자본잠식", ZERO_DENOM: "분모 0", PARTIAL: "일부 지역만",
  INCONSISTENT: "누적 차분 불일치",
};

export const CLOSE_REASON: Record<string, string> = {
  RESOLVED: "조건 해소", SUPERSEDED: "최신 시점으로 대체", RULE_CHANGED: "규칙 버전 변경", EXPIRED: "기간 경과",
};
