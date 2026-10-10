import { useEffect, useState } from "react";
import { loadedSiteConfig, siteConfig } from "../api/config";
import type { SiteConfig } from "../api/types";

/** 설정을 받지 못했을 때 — 로컬과 같은 동작(데모 아님) */
const FALLBACK: SiteConfig = { kakaoJsKey: "", demo: false };

/** 화면 설정(/api/config) — 받기 전에는 null. 여러 컴포넌트가 써도 한 번만 받음 */
export function useSiteConfig(): SiteConfig | null {
  const [config, setConfig] = useState<SiteConfig | null>(loadedSiteConfig);
  useEffect(() => {
    if (config) return;
    let live = true;
    siteConfig().then((c) => { if (live) setConfig(c); }, () => { if (live) setConfig(FALLBACK); });
    return () => { live = false; };
  }, [config]);
  return config;
}

/** 공개 데모인지 — 모르는 동안 null (ADR-031 덧붙임) */
export function useDemo(): boolean | null {
  return useSiteConfig()?.demo ?? null;
}
