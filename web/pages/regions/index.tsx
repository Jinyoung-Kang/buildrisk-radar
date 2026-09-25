import Link from "next/link";
import { useRouter } from "next/router";
import { useCallback, useMemo } from "react";
import Layout from "@/components/Layout";
import RegionMap from "@/components/RegionMap";
import RegionPanel from "@/components/RegionPanel";
import { Card, ErrorBox } from "@/components/ui";
import { qs, type GeoJson, type Meta, type RegionList, type RegionProps } from "@/lib/api";
import { makeScale, MISSING } from "@/lib/colors";
import { num, STATUS_LABEL, withUnit, ym } from "@/lib/format";
import { useApi } from "@/lib/useApi";

const SIGNED = new Set(["UNSOLD_3M_CHG", "PRICE_IDX_3M_CHG", "JEONSE_IDX_3M_CHG", "JEONSE_SALE_GAP"]);

export default function Regions() {
  const router = useRouter();
  const metric = (router.query.metric as string) || "UNSOLD_PER_1K_HH";
  const period = (router.query.period as string) || "";
  const sido = (router.query.sido as string) || "";
  const selected = (router.query.r as string) || null;
  // 화면 상태는 알려진 키만 URL 에 둠 (쿼리 문자열의 임의 키를 객체에 펼치지 않음)
  const set = (patch: Partial<Record<"metric" | "period" | "sido" | "r", string | null>>) => {
    const cur: Record<string, string | null> = { metric: router.query.metric as string, period, sido, r: selected };
    const next: Record<string, string> = {};
    for (const k of ["metric", "period", "sido", "r"] as const) {
      const v = k in patch ? patch[k] : cur[k];
      if (v) next[k] = v;
    }
    router.replace({ query: next }, undefined, { shallow: true });
  };
  const meta = useApi<Meta>("/meta");
  const list = useApi<RegionList>(router.isReady ? `/regions${qs({ metric, period, sido })}` : null);
  const geo = useApi<GeoJson>(router.isReady ? `/regions/geojson${qs({ metric, period, sido })}` : null);
  const def = meta.data?.metrics.find((m) => m.code === metric);
  const regionDefs = meta.data?.metrics.filter((m) => m.target === "REGION") ?? [];
  const scale = useMemo(() => makeScale((geo.data?.features ?? []).map((f) => f.properties.value).filter((v): v is number => v != null),
    SIGNED.has(metric), def?.higherIsRisk ?? true), [geo.data, metric, def]);
  const colorOf = useCallback((p: RegionProps) => scale.color(p.value), [scale]);
  const unit = def?.unit ?? "";
  const tooltipOf = useCallback((p: RegionProps) =>
    `${def?.nameKo ?? metric}: <b>${p.value == null ? STATUS_LABEL[p.status] ?? "없음" : withUnit(p.value, unit)}</b>${p.alertCount ? `<br/>경보 ${p.alertCount}건` : ""}`,
    [def, metric, unit]);
  const sidos = useMemo(() => {
    const m = new Map<string, string>();
    list.data?.items.forEach((r) => m.set(r.sidoCd, r.sidoName));
    return Array.from(m.entries()).sort();
  }, [list.data]);
  const ranked = (list.data?.items ?? []).filter((r) => r.value != null);

  return (
    <Layout title="지역 지도">
      <div className="flex flex-wrap gap-3 items-end mb-4">
        <div>
          <h1 className="text-xl font-semibold tracking-tight">시군구 주택시장 지도</h1>
          <p className="text-sm text-ink2 mt-1">{def?.formula ?? ""} · 출처 {def?.source ?? ""}</p>
        </div>
        <div className="flex flex-wrap gap-2 ml-auto text-sm">
          <label className="flex flex-col text-xs text-ink2">지표
            <select className="mt-1 bg-raised border border-line rounded-lg px-2 py-1.5 text-sm focus-ring" value={metric}
              onChange={(e) => set({ metric: e.target.value, period: null })}>
              {regionDefs.map((m) => <option key={m.code} value={m.code}>{m.nameKo} ({m.unit})</option>)}
            </select>
          </label>
          <label className="flex flex-col text-xs text-ink2">기간
            <select className="mt-1 bg-raised border border-line rounded-lg px-2 py-1.5 text-sm focus-ring" value={period || list.data?.period || ""}
              onChange={(e) => set({ period: e.target.value })}>
              {(list.data?.periods ?? []).slice(0, 36).map((p) => <option key={p} value={p}>{ym(p)}</option>)}
            </select>
          </label>
          <label className="flex flex-col text-xs text-ink2">시도
            <select className="mt-1 bg-raised border border-line rounded-lg px-2 py-1.5 text-sm focus-ring" value={sido}
              onChange={(e) => set({ sido: e.target.value, r: null })}>
              <option value="">전국</option>
              {sidos.map(([cd, nm]) => <option key={cd} value={cd}>{nm}</option>)}
            </select>
          </label>
        </div>
      </div>
      <ErrorBox error={geo.error ?? list.error} />
      <div className="grid lg:grid-cols-[1fr_380px] gap-4">
        <div>
          <RegionMap geo={geo.data} colorOf={colorOf} tooltipOf={tooltipOf} selected={selected}
            onSelect={(cd) => set({ r: cd })} className="h-[640px]" />
          <div className="flex flex-wrap items-center gap-3 mt-2 text-xs text-ink2" aria-label="범례">
            <span className="font-medium">{def?.nameKo} ({unit})</span>
            <div className="flex items-start pb-3">
              {scale.colors.map((c, i) => (
                <div key={i} className="relative">
                  <span className="block w-9 h-3" style={{ background: c }} />
                  {i < scale.breaks.length && (
                    <span className="absolute top-3.5 right-0 translate-x-1/2 text-[10px] text-muted tabular whitespace-nowrap">
                      {num(scale.breaks[i], Math.abs(scale.breaks[i]) < 1 ? 2 : 1)}</span>
                  )}
                </div>
              ))}
            </div>
            <span className="flex items-center gap-1"><span className="w-3 h-3 inline-block border border-line" style={{ background: MISSING, opacity: 0.35 }} />데이터 없음(흐리게)</span>
            {scale.kind === "div" && <span className="text-muted">빨강 = 위험 방향, 회색 = 변화 거의 없음</span>}
            <span className="ml-auto text-muted">{ym(geo.data?.meta.period)} · 경계 {geo.data?.features.length ?? 0}곳 · calcRun {geo.data?.meta.calcRunId?.slice(0, 8) ?? "–"}</span>
          </div>
        </div>
        <Card>
          {selected ? <RegionPanel key={selected} regionCd={selected} compact /> : (
            <div>
              <h3 className="text-sm font-semibold mb-2">{!def ? "순위" : def.higherIsRisk ? "값이 큰 순" : "값이 작은 순"} (지도에서 지역을 누르면 상세)</h3>
              <ol className="text-sm divide-y divide-line max-h-[560px] overflow-y-auto">
                {(def?.higherIsRisk ? ranked : [...ranked].reverse()).slice(0, 40).map((r, i) => (
                  <li key={r.regionCd} className="py-1.5 flex gap-2 items-center">
                    <span className="text-xs text-muted w-5 tabular">{i + 1}</span>
                    <button className="hover:underline text-left flex-1 focus-ring" onClick={() => set({ r: r.regionCd })}>{r.fullName}</button>
                    <span className="tabular text-ink2">{withUnit(r.value, unit)}</span>
                    {r.alertCount > 0 && <span className="text-[11px] text-crit">▲{r.alertCount}</span>}
                  </li>
                ))}
              </ol>
              <p className="text-[11px] text-muted mt-3">일반구가 있는 시는 시 단위로 합쳐 보여 줍니다. 매핑되지 않은 출처 지역은 <Link className="underline" href="/admin/mapping">매핑</Link> 화면에서 확인하세요.</p>
            </div>
          )}
        </Card>
      </div>
    </Layout>
  );
}
