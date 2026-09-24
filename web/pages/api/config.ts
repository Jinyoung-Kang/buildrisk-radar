import type { NextApiRequest, NextApiResponse } from "next";

/** 브라우저에 내려 보내는 유일한 키 — 카카오 JS 키(도메인 제한 키) */
export default function handler(_req: NextApiRequest, res: NextApiResponse) {
  res.setHeader("Cache-Control", "no-store");
  res.json({ kakaoJsKey: process.env.NEXT_PUBLIC_KAKAO_JS_KEY || "" });
}
