import Link from "next/link";
import { TrendLine } from "@/components/Charts";
import { Empty, Loading, SeverityBadge, StatusPill } from "@/components/ui";
import type { RegionSeries } from "@/lib/api";
import { int, num, ym } from "@/lib/format";
import { useApi } from "@/lib/useApi";

/** 지역 카드 — 미분양 추이 · 가격지수 추이 · 경보 (지도 옆, 지역 상세 화면 공용) */
export default function RegionPanel({ regionCd, compact }: { regionCd: string; compact?: boolean }) {
  const { data } = useApi<RegionSeries>(`/regions/${regionCd}/series`);
  if (!data) return <Loading />;
  const stat = (c: string) => data.stats.find((s) => s.code === c)?.points ?? [];
  const tail = compact ? -24 : -37;
  const unsold = stat("UNSOLD").slice(tail).map((p) => ({ x: ym(p.period), v: p.value ?? null }));
  const sale = Object.fromEntries(stat("SALE_IDX").map((p) => [p.period, p.value]));
  const jeonse = Object.fromEntries(stat("JEONSE_IDX").map((p) => [p.period, p.value]));
  const idxPeriods = Array.from(new Set([...Object.keys(sale), ...Object.keys(jeonse)])).sort().slice(tail);
  const idx = idxPeriods.map((p) => ({ x: ym(p), sale: sale[p] ?? null, jeonse: jeonse[p] ?? null }));
  const hh = stat("HOUSEHOLDS").at(-1);
  const latest = (code: string) => data.metrics.find((m) => m.code === code)?.points.filter((p) => p.value != null).at(-1);
  const per1k = latest("UNSOLD_PER_1K_HH"), chg = latest("UNSOLD_3M_CHG"), units = latest("UNSOLD_UNITS");
  return (
    <div className="space-y-4">
      <div>
        <div className="text-xs text-muted">{data.sidoName}</div>
        <h3 className="text-lg font-semibold">{data.name}</h3>
        {data.children.length > 0 && <div className="text-xs text-muted">일반구 {data.children.join(", ")} 합산</div>}
      </div>
      <dl className="grid grid-cols-3 gap-2 text-center">
        <div className="bg-page rounded-lg py-2"><dt className="text-[11px] text-ink2">미분양</dt><dd className="font-semibold tabular">{int(units?.value)}호</dd></div>
        <div className="bg-page rounded-lg py-2"><dt className="text-[11px] text-ink2">천 가구당</dt><dd className="font-semibold tabular">{num(per1k?.value, 2)}</dd></div>
        <div className="bg-page rounded-lg py-2"><dt className="text-[11px] text-ink2">3개월 증감</dt><dd className="font-semibold tabular">{chg?.value != null ? `${chg.value > 0 ? "+" : ""}${num(chg.value)}%` : "–"}</dd></div>
      </dl>
      <div className="text-[11px] text-muted -mt-2">기준 {ym(units?.period)} · 총가구 {int(hh?.value)} ({hh?.period ?? "–"}년)</div>
      <div>
        <div className="text-xs font-medium mb-1">미분양 주택 (호)</div>
        {unsold.length ? <TrendLine data={unsold} series={[{ key: "v", name: "미분양" }]} height={compact ? 150 : 220} fmt={(v) => int(v)} />
          : <Empty>KOSIS 미분양 데이터가 없습니다</Empty>}
      </div>
      <div>
        <div className="text-xs font-medium mb-1">아파트 가격지수 (R-ONE)</div>
        {idx.length ? <TrendLine data={idx} series={[{ key: "sale", name: "매매" }, { key: "jeonse", name: "전세" }]} height={compact ? 160 : 220} fmt={(v) => num(v, 1)} />
          : <Empty>R-ONE 아파트 가격지수 조사 대상이 아니거나 아직 수집 전입니다</Empty>}
      </div>
      <div>
        <div className="text-xs font-medium mb-1">경보</div>
        {data.alerts.length === 0 ? <div className="text-xs text-muted">없음</div> : (
          <ul className="space-y-1.5">
            {data.alerts.slice(0, compact ? 4 : 20).map((a) => (
              <li key={a.alertId} className="flex items-center gap-2 text-sm">
                <SeverityBadge s={a.severity} />
                <Link href={`/alerts?id=${a.alertId}`} className="hover:underline truncate flex-1">{a.title}</Link>
                <span className="text-[11px] text-muted">{ym(a.asOf)}</span><StatusPill status={a.status} />
              </li>
            ))}
          </ul>
        )}
      </div>
      {compact && <Link href={`/regions/${data.regionCd}`} className="text-sm text-accent hover:underline">지역 상세 →</Link>}
    </div>
  );
}
