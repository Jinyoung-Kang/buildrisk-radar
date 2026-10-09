// 지역 지도 — 경계와 지표 값 잇기 · 색 기준 · 투영
import type { Boundaries, GeoJson, RegionList, RegionRow, RegionSeries } from "@/lib/api";
import { ym } from "./format";

/** 부호가 있는 지표는 0 을 중심으로 한 발산형 색 (증가·감소를 구분) */
export const SIGNED_METRICS = new Set(["UNSOLD_3M_CHG", "PRICE_IDX_3M_CHG", "JEONSE_IDX_3M_CHG", "JEONSE_SALE_GAP", "TRADE_YOY", "PRICE_M2_YOY"]);

/** 경계(버전마다 한 번 받음)에 값 목록을 이어 GeoJSON 으로 — 값 목록에 없는 지역은 그리지 않음, 값이 없으면 MISSING */
export function joinRegionValues(bounds: Boundaries, list: RegionList): GeoJson {
  const byCd = new Map(list.items.map((r) => [r.regionCd, r]));
  const features = bounds.features.filter((f) => byCd.has(f.properties.regionCd)).map((f) => {
    const r = byCd.get(f.properties.regionCd)!;
    return { ...f, properties: { ...f.properties, value: r.value ?? null, status: r.status ?? "MISSING", alertCount: r.alertCount } };
  });
  return { type: "FeatureCollection", features, meta: { metric: list.metric, nameKo: "", unit: list.unit,
    period: list.period, calcRunId: list.calcRunId, sources: [] } };
}

/** 시도 선택 목록 (코드 순) */
export function sidoOptions(items: RegionRow[]): [string, string][] {
  const m = new Map<string, string>();
  items.forEach((r) => m.set(r.sidoCd, r.sidoName));
  return Array.from(m.entries()).sort();
}

/** SVG 대체 지도의 경로 — 등장방형 투영(위도 36° 보정), 가로 1000 에 맞춤 */
export function projectShapes(geo: GeoJson): { d: string[]; W: number; H: number } {
  const k = Math.cos((36 * Math.PI) / 180);
  const polys = geo.features.map((f) => {
    const parts = (f.geometry.type === "Polygon" ? [f.geometry.coordinates] : f.geometry.coordinates) as number[][][][];
    return parts.map((rings) => rings.map((ring) => ring.map(([lon, lat]) => [lon * k, -lat])));
  });
  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const parts of polys) for (const rings of parts) for (const ring of rings) for (const [x, y] of ring) {
    if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y;
  }
  const W = 1000, s = W / (maxX - minX || 1), H = (maxY - minY) * s;
  const d = polys.map((parts) => parts.map((rings) => rings.map((ring) =>
    "M" + ring.map(([x, y]) => `${((x - minX) * s).toFixed(1)},${((y - minY) * s).toFixed(1)}`).join("L") + "Z").join("")).join(""));
  return { d, W, H };
}

type Pt = { x: string; v: number | null };
type Latest = RegionSeries["metrics"][number]["points"][number] | undefined;

/** 지역 카드 — 통계 · 지표를 차트 점과 최신 값으로. compact(지도 옆)면 2년 · 1년, 아니면 3년 · 2년 */
export function regionPanel(data: RegionSeries, compact: boolean) {
  const stat = (c: string) => data.stats.find((s) => s.code === c)?.points ?? [];
  const pts = (c: string, n: number): Pt[] => stat(c).slice(n).map((p) => ({ x: ym(p.period), v: p.value ?? null }));
  const tail = compact ? -24 : -37, tradeTail = compact ? -13 : -25;
  const sale = Object.fromEntries(stat("SALE_IDX").map((p) => [p.period, p.value]));
  const jeonse = Object.fromEntries(stat("JEONSE_IDX").map((p) => [p.period, p.value]));
  const idxPeriods = Array.from(new Set([...Object.keys(sale), ...Object.keys(jeonse)])).sort().slice(tail);
  /** 값이 있는 마지막 시점 */
  const latest = (code: string): Latest => data.metrics.find((m) => m.code === code)?.points.filter((p) => p.value != null).at(-1);
  return {
    unsold: pts("UNSOLD", tail),
    idx: idxPeriods.map((p) => ({ x: ym(p), sale: sale[p] ?? null, jeonse: jeonse[p] ?? null })),
    households: stat("HOUSEHOLDS").at(-1),
    trades: pts("TRADE_CNT", tradeTail),
    priceM2: pts("PRICE_M2", tradeTail),
    per1k: latest("UNSOLD_PER_1K_HH"), chg: latest("UNSOLD_3M_CHG"), units: latest("UNSOLD_UNITS"),
    tradeYoy: latest("TRADE_YOY"), cancel: latest("CANCEL_RATE"), priceYoy: latest("PRICE_M2_YOY"), tradeCount: latest("TRADE_COUNT"),
  };
}
