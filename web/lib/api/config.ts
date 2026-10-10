// 화면 설정 — Next 서버의 /api/config(런타임 환경변수, .env 를 바꿔도 재빌드 불필요). 백엔드 /api/v1 이 아니라 client 를 거치지 않음.
// 머리말 · 권한 안내 · 지도가 함께 써도 페이지를 연 동안 한 번만 받음 (실패하면 다음에 다시)
import { parseSiteConfig } from "../domain/demo";
import type { SiteConfig } from "./types";

let shared: Promise<SiteConfig> | null = null;
let loaded: SiteConfig | null = null;

export function siteConfig(): Promise<SiteConfig> {
  if (!shared) {
    const p = fetch("/api/config", { credentials: "same-origin" })
      .then((r) => { if (!r.ok) throw new Error(`설정을 받지 못했습니다 (${r.status})`); return r.json(); })
      .then((body) => (loaded = parseSiteConfig(body)));
    p.catch(() => { if (shared === p) shared = null; });
    shared = p;
  }
  return shared;
}

/** 이미 받은 설정 — 화면을 옮겨 다시 그릴 때 기다리지 않게 (없으면 null) */
export function loadedSiteConfig(): SiteConfig | null {
  return loaded;
}
