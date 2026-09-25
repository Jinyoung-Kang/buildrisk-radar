import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { Empty, ErrorBox, Loading, SeverityBadge } from "@/components/ui";
import type { Prices } from "@/lib/api";
import { SEVERITY } from "@/lib/colors";
import { int } from "@/lib/format";
import { useApi } from "@/lib/useApi";

/** 종가 추이 + 경보 처음 발생일 표시 (금융위 주식시세, 수정주가 아님) */
export default function PriceChart({ corp }: { corp: string }) {
  const { data, error } = useApi<Prices>(`/companies/${corp}/prices?days=1095`);
  if (error) return <ErrorBox error={error} />;
  if (!data) return <Loading />;
  if (!data.stockCode) return <Empty>상장 종목코드가 없습니다.</Empty>;
  if (data.bars.length === 0) return <Empty>주가가 아직 없습니다 — stockPriceJob 을 실행하세요.</Empty>;
  const dates = new Set(data.bars.map((b) => b.d));
  const markers = data.alerts.filter((a) => dates.has(a.date) || a.date >= data.bars[0].d);
  const snap = (d: string) => data.bars.find((b) => b.d >= d)?.d ?? d;   // 휴일 경보 → 다음 거래일
  return (
    <div>
      <ResponsiveContainer width="100%" height={320}>
        <LineChart data={data.bars} margin={{ top: 12, right: 16, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} />
          <XAxis dataKey="d" tickLine={false} minTickGap={48} tickFormatter={(d: string) => d.slice(2, 7).replace("-", ".")} />
          <YAxis width={64} tickLine={false} axisLine={false} domain={["auto", "auto"]} tickFormatter={(v) => int(v)} />
          <Tooltip contentStyle={{ background: "rgb(var(--raised))", border: "1px solid rgb(var(--line))", borderRadius: 8, fontSize: 12 }}
            formatter={(v) => [`${int(Number(v))}원`, "종가"]} labelFormatter={(d) => String(d)} />
          {markers.map((a) => (
            <ReferenceLine key={a.alertId} x={snap(a.date)} stroke={SEVERITY[a.severity]?.color ?? "#999"} strokeDasharray="3 3" />
          ))}
          <Line dataKey="c" name="종가" stroke="rgb(var(--ink))" strokeWidth={1.5} dot={false} isAnimationActive={false} />
        </LineChart>
      </ResponsiveContainer>
      <div className="text-[11px] text-muted mt-1">점선 = 경보 근거 공시가 공개된 날 (백테스트와 같은 기준). 종가는 {data.source}.</div>
      {markers.length > 0 && (
        <ul className="mt-3 space-y-1 text-sm max-h-48 overflow-auto">
          {markers.slice().reverse().map((a) => (
            <li key={a.alertId} className="flex gap-2 items-center"><SeverityBadge s={a.severity} />
              <span className="text-xs text-muted tabular w-20">{a.date}</span><span className="truncate">{a.ruleCode} {a.title}</span></li>))}
        </ul>
      )}
    </div>
  );
}
