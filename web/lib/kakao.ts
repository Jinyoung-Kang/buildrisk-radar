// 카카오 지도 SDK 로더 — 키는 런타임에 /api/config 에서 받습니다(.env 변경 시 재빌드 불필요).
/* eslint-disable @typescript-eslint/no-explicit-any */
import { siteConfig } from "./api/config";

declare global {
  interface Window { kakao: any }
}

let loading: Promise<any> | null = null;
const SCRIPT_ID = "kakao-maps-sdk";

export function loadKakao(): Promise<any> {
  if (typeof window === "undefined") return Promise.reject(new Error("browser only"));
  if (window.kakao?.maps?.LatLng) return Promise.resolve(window.kakao);
  if (loading) return loading;
  loading = (async () => {
    const cfg = await siteConfig();   // 머리말 · 권한 안내와 같은 응답을 나눠 씀
    if (!cfg.kakaoJsKey) throw new Error("NEXT_PUBLIC_KAKAO_JS_KEY 가 .env 에 없습니다.");
    await new Promise<void>((resolve, reject) => {
      // 실패한 스크립트는 지우고 다시 넣음 — 남겨 두면 다시 시도할 때마다 겹쳐 쌓임 (VERIFICATION 149)
      document.getElementById(SCRIPT_ID)?.remove();
      const s = document.createElement("script");
      s.id = SCRIPT_ID;
      s.src = `https://dapi.kakao.com/v2/maps/sdk.js?appkey=${encodeURIComponent(cfg.kakaoJsKey)}&autoload=false`;
      s.async = true;
      s.onload = () => resolve();
      s.onerror = () => { s.remove(); reject(new Error("카카오 지도 SDK 를 불러오지 못했습니다. 카카오 개발자 콘솔의 플랫폼 Web 도메인에 http://localhost:3400 을 등록했는지 확인하세요.")); };
      document.head.appendChild(s);
    });
    await new Promise<void>((resolve) => window.kakao.maps.load(() => resolve()));
    return window.kakao;
  })();
  loading.catch(() => { loading = null; });
  return loading;
}

/** GeoJSON (Multi)Polygon → 카카오 Polygon path 목록 */
export function toPaths(kakao: any, geom: { type: string; coordinates: any }): any[][][] {
  const polys: number[][][][] = geom.type === "Polygon" ? [geom.coordinates] : geom.coordinates;
  return polys.map((rings) => rings.map((ring) => ring.map(([x, y]) => new kakao.maps.LatLng(y, x))));
}
