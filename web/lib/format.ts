const nf = new Intl.NumberFormat("ko-KR");

/**
 * 숫자 표시. fixed = 소수 자릿수를 고정(표의 열에서 자릿수가 들쭉날쭉하지 않게 — 45% · 45.7% 대신 45.0% · 45.7%).
 * signDisplay "negative": 반올림해 0 이 된 음수를 "-0" 으로 쓰지 않음 (백테스트 비교 기준 -0.03% → "-0%" 이던 문제)
 */
export function num(v: number | null | undefined, digits = 1, fixed = false): string {
  if (v === null || v === undefined || Number.isNaN(v)) return "–";
  const abs = Math.abs(v);
  const d = abs >= 1000 ? 0 : abs >= 100 ? Math.min(digits, 1) : digits;
  return new Intl.NumberFormat("ko-KR", { maximumFractionDigits: d, minimumFractionDigits: fixed ? d : 0, signDisplay: "negative" }).format(v);
}

/** 부호를 붙인 고정 소수 (+1.2 · −3.0 · 0.0). 0 으로 반올림되면 부호 없음 */
export function signed(v: number | null | undefined, digits = 1): string {
  if (v === null || v === undefined || Number.isNaN(v)) return "–";
  return new Intl.NumberFormat("ko-KR", { minimumFractionDigits: digits, maximumFractionDigits: digits, signDisplay: "exceptZero" }).format(v);
}

/** 반올림한 표시값 기준으로 음수인지 — "-0.0%" 를 빨갛게 칠하지 않도록 */
export function isNeg(v: number | null | undefined, digits = 1): boolean {
  return v != null && Math.round(v * 10 ** digits) < 0;
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
