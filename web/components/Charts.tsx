import { Bar, BarChart, CartesianGrid, Cell, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { SERIES } from "@/lib/colors";
import { num } from "@/lib/format";

type Pt = { x: string; [k: string]: string | number | null | undefined };

const tooltipStyle = {
  contentStyle: { background: "rgb(var(--raised))", border: "1px solid rgb(var(--line))", borderRadius: 8, fontSize: 12 },
  labelStyle: { color: "rgb(var(--ink2))" },
};

/** 데이터 범위를 clamp 안으로 자르고 1·2·5 단위의 반듯한 눈금을 만듦 (예: -60억·19 → -10~20, 눈금 10 간격) */
function clampedAxis(data: Pt[], keys: string[], clamp: [number, number], threshold?: number) {
  const vals = data.flatMap((d) => keys.map((k) => d[k])).filter((v): v is number => typeof v === "number" && Number.isFinite(v));
  if (!vals.length) return undefined;
  const lo0 = Math.max(Math.min(...vals, threshold ?? Infinity), clamp[0]);
  const hi0 = Math.min(Math.max(...vals, threshold ?? -Infinity), clamp[1]);
  const raw = (hi0 - lo0) / 4 || 1;
  const mag = 10 ** Math.floor(Math.log10(raw));
  const step = [1, 2, 5, 10].map((m) => m * mag).find((x) => x >= raw)!;
  const lo = Math.max(Math.floor(lo0 / step) * step, clamp[0]);
  const hi = Math.min(Math.ceil(hi0 / step) * step, clamp[1]);
  const ticks: number[] = [];
  for (let t = Math.ceil(lo / step) * step; t <= hi + step / 1e6; t += step) ticks.push(Number(t.toPrecision(12)));
  return { domain: [lo, hi] as [number, number], ticks };
}

/** 한 지표 추이 (시리즈 1개면 범례 없이 제목이 이름) — 임계선 선택.
 *  clamp: 축 범위 상·하한 — 분모가 0 에 가까운 비율(이자비용 5원 → -60억 배)이 축을 눌러 임계선이 안 보이는 것을 막음.
 *  범위를 넘는 점은 잘려 보이고, 툴팁은 실제 값(tipFmt)을 보여 줌 */
export function TrendLine({ data, series, fmt, tipFmt, threshold, thresholdLabel, clamp, height = 200 }: {
  data: Pt[]; series: { key: string; name: string }[]; fmt?: (v: number) => string; tipFmt?: (v: number) => string;
  threshold?: number; thresholdLabel?: string; clamp?: [number, number]; height?: number;
}) {
  const f = fmt ?? ((v: number) => num(v));
  const tf = tipFmt ?? f;
  const axis = clamp && clampedAxis(data, series.map((x) => x.key), clamp, threshold);
  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="x" tickLine={false} minTickGap={12} />
        <YAxis width={56} tickLine={false} axisLine={false} tickFormatter={(v) => f(v)}
          {...(axis ? { domain: axis.domain, ticks: axis.ticks, allowDataOverflow: true } : {})} />
        <Tooltip {...tooltipStyle} formatter={(v, n) => [typeof v === "number" ? tf(v) : String(v ?? "–"), String(n)]} />
        {threshold !== undefined && (
          <ReferenceLine y={threshold} stroke="#d03b3b" strokeDasharray="4 3"
            label={{ value: thresholdLabel ?? `임계 ${f(threshold)}`, position: "insideTopRight", fontSize: 11, fill: "#d03b3b" }} />
        )}
        {series.length > 1 && <Legend iconType="plainline" wrapperStyle={{ fontSize: 12 }} />}
        {series.map((s, i) => (
          <Line key={s.key} dataKey={s.key} name={s.name} stroke={SERIES[i]} strokeWidth={2} connectNulls={false}
            dot={{ r: 3, strokeWidth: 0, fill: SERIES[i] }} activeDot={{ r: 5, stroke: "rgb(var(--raised))", strokeWidth: 2 }}
            isAnimationActive={false} />
        ))}
      </LineChart>
    </ResponsiveContainer>
  );
}

/** 부호가 있는 값 막대 (음수 빨강·양수 파랑 또는 반대) */
export function SignedBars({ data, dataKey, name, fmt, negativeIsRisk = true, height = 200 }: {
  data: Pt[]; dataKey: string; name: string; fmt?: (v: number) => string; negativeIsRisk?: boolean; height?: number;
}) {
  const f = fmt ?? ((v: number) => num(v));
  return (
    <ResponsiveContainer width="100%" height={height}>
      <BarChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="x" tickLine={false} minTickGap={12} />
        <YAxis width={56} tickLine={false} axisLine={false} tickFormatter={(v) => f(v)} />
        <ReferenceLine y={0} stroke="rgb(var(--muted))" />
        <Tooltip {...tooltipStyle} cursor={{ fill: "rgb(var(--line) / 0.5)" }}
          formatter={(v) => [typeof v === "number" ? f(v) : String(v ?? "–"), name]} />
        <Bar dataKey={dataKey} name={name} radius={[4, 4, 4, 4]} maxBarSize={28} isAnimationActive={false}>
          {data.map((d, i) => {
            const v = Number(d[dataKey] ?? 0);
            const risky = negativeIsRisk ? v < 0 : v > 0;
            return <Cell key={i} fill={risky ? "#e34948" : "#2a78d6"} />;
          })}
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}
