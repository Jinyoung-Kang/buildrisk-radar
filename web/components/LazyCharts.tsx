import dynamic from "next/dynamic";

/**
 * 차트(Recharts ≈ 110 KB)를 처음 로드에서 빼고, 차트가 실제로 그려질 때 받습니다.
 * 기업 상세는 첫 탭(개요)에 차트가 없고, 지도는 지역을 고른 뒤에야 차트가 필요합니다.
 * 자리표시자 높이를 차트와 맞춰 로드 뒤 화면이 밀리지 않게 합니다.
 */
function ChartPlaceholder() {
  return <div className="h-[200px] rounded-lg bg-line/30 animate-pulse" aria-hidden />;
}

export const TrendLine = dynamic(() => import("./Charts").then((m) => m.TrendLine), { ssr: false, loading: ChartPlaceholder });
export const SignedBars = dynamic(() => import("./Charts").then((m) => m.SignedBars), { ssr: false, loading: ChartPlaceholder });
export const PriceChart = dynamic(() => import("./PriceChart"), { ssr: false, loading: ChartPlaceholder });
export const RegionPanel = dynamic(() => import("./RegionPanel"), { ssr: false, loading: ChartPlaceholder });
