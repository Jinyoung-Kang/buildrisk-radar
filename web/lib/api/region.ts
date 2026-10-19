// 지역 — /regions/* 조회 — 화면은 `import * as regionApi from "@/lib/api/region"` (ADR-030)
import { qs, query } from "./client";
import type { Boundaries, RegionContracts, RegionList, RegionPresales, RegionSeries, Sites } from "./types";

const e = encodeURIComponent;
export const list = (p: { metric?: string; period?: string; sido?: string }) => query<RegionList>(`/regions${qs(p)}`);
export const boundaries = (version: string) => query<Boundaries>(`/regions/boundaries${qs({ v: version })}`);
export const series = (cd: string) => query<RegionSeries>(`/regions/${e(cd)}/series`);
export const contracts = (cd: string) => query<RegionContracts>(`/regions/${e(cd)}/contracts`);
export const sites = (cd: string) => query<Sites>(`/regions/${e(cd)}/sites`);
export const presales = (cd: string) => query<RegionPresales>(`/regions/${e(cd)}/presales`);
