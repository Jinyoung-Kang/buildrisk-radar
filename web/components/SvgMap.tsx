import { useMemo, useState } from "react";
import type { GeoJson, RegionProps } from "@/lib/api";
import { MISSING } from "@/lib/colors";

/**
 * 카카오 지도를 쓸 수 없을 때의 대체 단계구분도 (SVG). 같은 GeoJSON 을 등장방형 투영(위도 36° 보정)으로 그립니다.
 * 배경 지도는 없지만 색·툴팁·선택은 카카오 지도와 같게 동작합니다.
 */
export default function SvgMap({ geo, colorOf, tooltipOf, selected, onSelect }: {
  geo: GeoJson; colorOf: (p: RegionProps) => string; tooltipOf: (p: RegionProps) => string;
  selected?: string | null; onSelect?: (cd: string) => void;
}) {
  const [hover, setHover] = useState<{ p: RegionProps; x: number; y: number } | null>(null);
  const shapes = useMemo(() => {
    const k = Math.cos((36 * Math.PI) / 180);
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    const polys = geo.features.map((f) => {
      const parts = (f.geometry.type === "Polygon" ? [f.geometry.coordinates] : f.geometry.coordinates) as number[][][][];
      return parts.map((rings) => rings.map((ring) => ring.map(([lon, lat]) => {
        const x = lon * k, y = -lat;
        if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y;
        return [x, y];
      })));
    });
    const W = 1000, s = W / (maxX - minX || 1), H = (maxY - minY) * s;
    const d = polys.map((parts) => parts.map((rings) => rings.map((ring) =>
      "M" + ring.map(([x, y]) => `${((x - minX) * s).toFixed(1)},${((y - minY) * s).toFixed(1)}`).join("L") + "Z").join("")).join(""));
    return { d, W, H };
  }, [geo]);
  return (
    <div className="absolute inset-0 overflow-hidden rounded-xl bg-page" onMouseLeave={() => setHover(null)}>
      <svg viewBox={`-10 -10 ${shapes.W + 20} ${shapes.H + 20}`} className="w-full h-full" role="img" aria-label="시군구 단계구분도">
        {geo.features.map((f, i) => {
          const sel = f.properties.regionCd === selected;
          return (
            <path key={f.properties.regionCd} d={shapes.d[i]} fill={colorOf(f.properties)} fillRule="evenodd"
              fillOpacity={colorOf(f.properties) === MISSING ? 0.3 : 1}
              stroke={sel ? "rgb(var(--ink))" : "rgb(var(--raised))"} strokeWidth={sel ? 2.5 : 0.6} className="cursor-pointer"
              onMouseMove={(e) => {
                const r = (e.currentTarget.ownerSVGElement as SVGSVGElement).getBoundingClientRect();
                setHover({ p: f.properties, x: e.clientX - r.left, y: e.clientY - r.top });
              }}
              onClick={() => onSelect?.(f.properties.regionCd)}>
              <title>{f.properties.fullName}</title>
            </path>
          );
        })}
      </svg>
      {hover && (
        <div className="map-tip absolute pointer-events-none" style={{ left: hover.x + 12, top: hover.y + 12 }}
          dangerouslySetInnerHTML={{ __html: `<b>${hover.p.fullName.replace(/</g, "&lt;")}</b><br/>${tooltipOf(hover.p)}` }} />
      )}
      <div className="absolute left-3 bottom-3 text-[11px] text-muted bg-raised/80 rounded px-2 py-1">
        배경 지도 없이 표시 중 (카카오 지도 로드 실패)
      </div>
    </div>
  );
}
