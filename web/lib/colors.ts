// 데이터 시각화 팔레트 (dataviz 기준 인스턴스). 순차 = 파랑 한 색 밝→어, 발산 = 파랑↔빨강 + 회색 중립.

export const SEQ = ["#cde2fb", "#9ec5f4", "#6da7ec", "#3987e5", "#256abf", "#184f95", "#0d366b"];
export const DIV_NEG = ["#184f95", "#3987e5", "#9ec5f4"];   // 위험 낮은 쪽(파랑) 진→연
export const DIV_POS = ["#f3b0a9", "#e66767", "#b8302f"];   // 위험 높은 쪽(빨강) 연→진
export const NEUTRAL = "#e4e3de";
export const MISSING = "#d4d3cd";

export const SERIES = ["#2a78d6", "#eb6834", "#1baf7a", "#eda100"];

export const SEVERITY: Record<string, { color: string; label: string; icon: string }> = {
  HIGH: { color: "#d03b3b", label: "높음", icon: "▲" },
  MEDIUM: { color: "#ec835a", label: "중간", icon: "◆" },
  LOW: { color: "#e0a000", label: "낮음", icon: "●" },
};

export type Scale = { kind: "seq" | "div"; breaks: number[]; colors: string[]; color: (v: number | null | undefined) => string };

/** 양수만 있는 크기 지표 → 분위수 7단계 순차, 부호가 있는 변화 지표 → 0 중심 발산 */
export function makeScale(values: number[], signed: boolean, higherIsRisk: boolean): Scale {
  const vs = values.filter((v) => Number.isFinite(v)).sort((a, b) => a - b);
  if (vs.length === 0) return { kind: "seq", breaks: [], colors: [], color: () => MISSING };
  if (!signed) {
    const n = SEQ.length;
    const breaks: number[] = [];
    for (let i = 1; i < n; i++) breaks.push(vs.length ? vs[Math.floor((i / n) * (vs.length - 1))] : 0);
    const uniq = Array.from(new Set(breaks));
    const colors = SEQ.slice(n - uniq.length - 1);
    return {
      kind: "seq", breaks: uniq, colors,
      color: (v) => (v === null || v === undefined ? MISSING : colors[uniq.filter((b) => v > b).length]),
    };
  }
  const maxAbs = Math.max(Math.abs(vs[0]), Math.abs(vs[vs.length - 1])) || 1;
  const step = maxAbs / 3;
  const breaks = [-2 * step, -step, -step / 6, step / 6, step, 2 * step];
  // 위험 방향이 빨강: higherIsRisk 면 양수가 빨강, 아니면 음수가 빨강
  const low = higherIsRisk ? DIV_NEG : [...DIV_POS].reverse();
  const high = higherIsRisk ? DIV_POS : [...DIV_NEG].reverse();
  const colors = [...low, NEUTRAL, ...high];
  return { kind: "div", breaks, colors, color: (v) => (v === null || v === undefined ? MISSING : colors[breaks.filter((b) => v > b).length]) };
}
