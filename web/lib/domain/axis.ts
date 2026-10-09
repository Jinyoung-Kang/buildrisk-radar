// 차트 축 — 반듯한 눈금

/**
 * 1·2·5 단위의 반듯한 눈금 (예: 0·7·14·21·28 → 0·10·20·30, 0·-0.65·-1.3 → 0·-0.5·-1·-1.5).
 * zero: 0 을 범위에 넣을지(크기를 비교하는 값) — 지수처럼 100 근처에서 움직이는 값은 false 로 두어야 변화가 보임.
 * clamp: 축 상·하한 — 분모가 0 에 가까운 비율(이자비용 5원 → -60억 배)이 축을 눌러 임계선이 안 보이는 것을 막음.
 */
export function niceAxis(values: number[], opts: { clamp?: [number, number]; threshold?: number; zero?: boolean } = {}) {
  const vals = values.filter((v) => Number.isFinite(v));
  if (!vals.length) return undefined;
  const [cLo, cHi] = opts.clamp ?? [-Infinity, Infinity];
  const extra = [opts.threshold, opts.zero === false ? undefined : 0].filter((v): v is number => v !== undefined);
  const lo0 = Math.max(Math.min(...vals, ...extra), cLo);
  const hi0 = Math.min(Math.max(...vals, ...extra), cHi);
  const raw = (hi0 - lo0) / 4 || Math.abs(hi0) / 4 || 1;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * mag).find((x) => x >= raw)!;
  const lo = Math.max(Math.floor(lo0 / step) * step, cLo);
  let hi = Math.min(Math.ceil(hi0 / step) * step, cHi);
  if (hi <= lo) hi = lo + step;                                   // 값이 모두 같을 때(예: 전부 0) 높이 0 인 축 방지
  const ticks: number[] = [];
  for (let t = Math.ceil(lo / step) * step; t <= hi + step / 1e6; t += step) ticks.push(Number(t.toPrecision(12)));
  return { domain: [lo, hi] as [number, number], ticks };
}
