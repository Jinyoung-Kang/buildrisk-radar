/* eslint-disable @typescript-eslint/no-explicit-any */
import { useEffect, useRef, useState } from "react";
import type { GeoJson, RegionProps } from "@/lib/api";
import { loadKakao, toPaths } from "@/lib/kakao";
import SvgMap from "./SvgMap";
import { MISSING } from "@/lib/colors";

type Props = {
  geo: GeoJson | null;
  colorOf: (p: RegionProps) => string;
  tooltipOf: (p: RegionProps) => string;
  selected?: string | null;
  onSelect?: (regionCd: string) => void;
  className?: string;
};

function esc(s: string) {
  return s.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]!));
}

/**
 * 카카오 지도 위 시군구 단계구분도 (FR-602). 경계(geometry)가 같으면 폴리곤을 다시 만들지 않고 색만 칠합니다
 * — 지표·기간을 바꿔도 즉시 반응.
 */
export default function RegionMap({ geo, colorOf, tooltipOf, selected, onSelect, className }: Props) {
  const el = useRef<HTMLDivElement>(null);
  const map = useRef<any>(null);
  const kakaoRef = useRef<any>(null);
  const polys = useRef<Map<string, { shapes: any[]; props: RegionProps }>>(new Map());
  const tip = useRef<any>(null);
  const cb = useRef({ colorOf, tooltipOf, onSelect, selected });
  cb.current = { colorOf, tooltipOf, onSelect, selected };
  const [err, setErr] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const bounds = useRef<any>(null);
  const userMoved = useRef(false);   // 사용자가 끌거나 확대하기 전까지는 크기가 바뀔 때마다 전국에 다시 맞춤

  /** 컨테이너 크기가 정해진 뒤 전체 경계에 맞춤 (flex 레이아웃은 마운트 시점에 크기가 0 일 수 있음) */
  function fit() {
    if (!map.current || !bounds.current || bounds.current.isEmpty()) return;
    map.current.relayout();
    map.current.setBounds(bounds.current, 16, 16, 16, 16);
  }

  useEffect(() => {
    let cancelled = false;
    loadKakao().then((kakao) => {
      if (cancelled || !el.current) return;
      kakaoRef.current = kakao;
      map.current = new kakao.maps.Map(el.current, { center: new kakao.maps.LatLng(36.3, 127.8), level: 13 });
      map.current.addControl(new kakao.maps.ZoomControl(), kakao.maps.ControlPosition.RIGHT);
      tip.current = new kakao.maps.CustomOverlay({ yAnchor: 1.3, zIndex: 10 });
      kakao.maps.event.addListener(map.current, "dragstart", () => { userMoved.current = true; });
      setReady(true);
    }).catch((e) => setErr(e.message));
    // 레이아웃이 자리 잡는 동안(데이터 도착·스크롤바) 크기가 바뀌면 다시 맞춤 — 한 번만 맞추면 치우쳐 보였음
    const ro = new ResizeObserver(() => { if (userMoved.current) map.current?.relayout(); else fit(); });
    const onWheel = () => { userMoved.current = true; };
    if (el.current) { ro.observe(el.current); el.current.addEventListener("wheel", onWheel, { passive: true }); }
    const node = el.current;
    return () => { cancelled = true; ro.disconnect(); node?.removeEventListener("wheel", onWheel); };
  }, []);

  function paint() {
    polys.current.forEach(({ shapes, props }, cd) => {
      const sel = cd === cb.current.selected;
      const fill = cb.current.colorOf(props);
      // 데이터 없는 지역은 흐리게 — '변화 거의 없음'(회색 중립)과 구분
      shapes.forEach((s) => s.setOptions({ fillColor: fill, fillOpacity: fill === MISSING ? 0.25 : 0.85,
        strokeColor: sel ? "#0b0b0b" : "#ffffff", strokeWeight: sel ? 3 : 1, zIndex: sel ? 5 : 1 }));
    });
  }

  useEffect(() => {
    const kakao = kakaoRef.current;
    if (!ready || !kakao || !geo) return;
    const same = geo.features.length === polys.current.size && geo.features.every((f) => polys.current.has(f.properties.regionCd));
    if (same) {
      geo.features.forEach((f) => { const e = polys.current.get(f.properties.regionCd); if (e) e.props = f.properties; });
      paint();
      return;
    }
    polys.current.forEach(({ shapes }) => shapes.forEach((s) => s.setMap(null)));
    polys.current.clear();
    const b = new kakao.maps.LatLngBounds();
    for (const f of geo.features) {
      if (!f.geometry) continue;
      const entry = { shapes: [] as any[], props: f.properties };
      entry.shapes = toPaths(kakao, f.geometry).map((path) => {
        path.forEach((ring) => ring.forEach((ll: any) => b.extend(ll)));
        const poly = new kakao.maps.Polygon({ map: map.current, path, strokeWeight: 1, strokeColor: "#ffffff",
          strokeOpacity: 1, fillColor: "#e4e3de", fillOpacity: 0.85 });
        kakao.maps.event.addListener(poly, "mouseover", (e: any) => hover(entry.props, e.latLng, true));
        kakao.maps.event.addListener(poly, "mousemove", (e: any) => tip.current?.setPosition(e.latLng));
        kakao.maps.event.addListener(poly, "mouseout", () => hover(entry.props, null, false));
        kakao.maps.event.addListener(poly, "click", () => { tip.current?.setMap(null); cb.current.onSelect?.(entry.props.regionCd); });
        return poly;
      });
      polys.current.set(f.properties.regionCd, entry);
    }
    paint();
    bounds.current = b;
    requestAnimationFrame(fit);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ready, geo]);

  useEffect(() => { if (ready) paint(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [colorOf, selected, ready]);

  function hover(p: RegionProps, latLng: any, on: boolean) {
    const e = polys.current.get(p.regionCd);
    if (!e) return;
    const sel = p.regionCd === cb.current.selected;
    e.shapes.forEach((s) => s.setOptions({ strokeColor: on || sel ? "#0b0b0b" : "#ffffff", strokeWeight: on || sel ? 2 : 1 }));
    if (on && latLng) {
      tip.current.setContent(`<div class="map-tip"><b>${esc(p.fullName)}</b><br/>${cb.current.tooltipOf(p)}</div>`);
      tip.current.setPosition(latLng);
      tip.current.setMap(map.current);
    } else tip.current?.setMap(null);
  }

  return (
    <div className={`relative ${className ?? ""}`}>
      <div ref={el} className="absolute inset-0 rounded-xl overflow-hidden bg-line/40" aria-label="시군구 지도" role="application" />
      {ready && !err && (
        <button type="button" onClick={() => { userMoved.current = false; fit(); }}
          className="absolute top-3 left-3 z-10 text-xs px-2.5 py-1.5 rounded-md bg-raised/95 border border-line shadow-card hover:bg-page focus-ring">
          전국 보기</button>
      )}
      {err && geo && <SvgMap geo={geo} colorOf={colorOf} tooltipOf={tooltipOf} selected={selected} onSelect={onSelect} />}
      {err && (
        <details className="absolute top-3 left-3 max-w-md text-xs bg-raised/90 rounded-lg shadow-card px-3 py-2">
          <summary className="cursor-pointer text-serious">카카오 지도를 불러오지 못했습니다</summary>
          <p className="mt-1 text-ink2">{err}</p>
        </details>
      )}
    </div>
  );
}
