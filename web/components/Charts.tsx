import { Bar, BarChart, CartesianGrid, Cell, Legend, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { SERIES } from "@/lib/colors";
import { num } from "@/lib/format";

type Pt = { x: string; [k: string]: string | number | null | undefined };

const tooltipStyle = {
  contentStyle: { background: "rgb(var(--raised))", border: "1px solid rgb(var(--line))", borderRadius: 8, fontSize: 12 },
  labelStyle: { color: "rgb(var(--ink2))" },
};

/** 한 지표 추이 (시리즈 1개면 범례 없이 제목이 이름) — 임계선 선택 */
export function TrendLine({ data, series, fmt, threshold, thresholdLabel, height = 200 }: {
  data: Pt[]; series: { key: string; name: string }[]; fmt?: (v: number) => string;
  threshold?: number; thresholdLabel?: string; height?: number;
}) {
  const f = fmt ?? ((v: number) => num(v));
  return (
    <ResponsiveContainer width="100%" height={height}>
      <LineChart data={data} margin={{ top: 8, right: 16, bottom: 0, left: 0 }}>
        <CartesianGrid vertical={false} />
        <XAxis dataKey="x" tickLine={false} minTickGap={12} />
        <YAxis width={56} tickLine={false} axisLine={false} tickFormatter={(v) => f(v)} />
        <Tooltip {...tooltipStyle} formatter={(v, n) => [typeof v === "number" ? f(v) : String(v ?? "–"), String(n)]} />
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
