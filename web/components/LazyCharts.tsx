import dynamic from "next/dynamic";
import type { ComponentProps, ComponentType } from "react";
import type * as Charts from "./Charts";   // 타입만 — 번들에는 들어가지 않음

/**
 * 차트(Recharts ≈ 110 KB)를 처음 로드에서 빼고, 차트가 실제로 그려질 때 받습니다.
 * 기업 상세는 첫 탭(개요)에 차트가 없고, 지도는 지역을 고른 뒤에야, 지역 상세는 숫자 · 표가 먼저 보이고 차트가 뒤따릅니다.
 * 자리표시자 높이를 차트와 맞춰 로드 뒤 화면이 밀리지 않게 합니다.
 */
function ChartPlaceholder() {
  return <div className="h-[200px] rounded-lg bg-line/30 animate-pulse" aria-hidden />;
}

/** 꺾은선 · 막대는 받은 height 만큼 자리를 먼저 잡음 — 차트(130~220px)가 뜰 때 아래 내용이 밀리지 않게 */
function FillPlaceholder() {
  return <div className="h-full rounded-lg bg-line/30 animate-pulse" aria-hidden />;
}

function sized<P extends { height?: number }>(Chart: ComponentType<P>) {
  return function SizedChart(props: P) {
    return <div style={{ height: props.height ?? 200 }}><Chart {...props} /></div>;
  };
}

export const TrendLine = sized<ComponentProps<typeof Charts.TrendLine>>(
  dynamic(() => import("./Charts").then((m) => m.TrendLine), { ssr: false, loading: FillPlaceholder }));
export const SignedBars = sized<ComponentProps<typeof Charts.SignedBars>>(
  dynamic(() => import("./Charts").then((m) => m.SignedBars), { ssr: false, loading: FillPlaceholder }));
export const PriceChart = dynamic(() => import("./PriceChart"), { ssr: false, loading: ChartPlaceholder });
export const RegionPanel = dynamic(() => import("./RegionPanel"), { ssr: false, loading: ChartPlaceholder });
