import type { NextApiRequest, NextApiResponse } from "next";
import { siteConfigFromEnv } from "@/lib/domain/demo";

/** 브라우저에 내려 보내는 화면 설정 — 키는 카카오 JS 키(도메인 제한 키) 하나뿐, demo 는 공개 데모 여부(ADR-031). 런타임 환경변수에서 */
export default function handler(_req: NextApiRequest, res: NextApiResponse) {
  res.setHeader("Cache-Control", "no-store");
  res.json(siteConfigFromEnv(process.env));
}
