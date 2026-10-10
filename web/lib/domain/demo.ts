// 공개 데모(AWS) — 앞단 프록시가 /api/ 의 GET · HEAD 가 아닌 요청을 모두 막아 로그인할 수 없으므로 로그인 링크 · 안내를 숨김
// (ADR-031 덧붙임, VERIFICATION 171). 로컬(설정 없음)은 예전과 같음
import type { Role, SiteConfig } from "@/lib/api";

export const DEMO_READ_ONLY = "데모에서는 조회만 가능합니다";

/** /api/config 응답 — 데모는 BUILDRISK_PUBLIC_DEMO 가 정확히 "true" 일 때만(없거나 다른 값이면 데모 아님) */
export function siteConfigFromEnv(env: Record<string, string | undefined>): SiteConfig {
  return { kakaoJsKey: env.NEXT_PUBLIC_KAKAO_JS_KEY || "", demo: env.BUILDRISK_PUBLIC_DEMO === "true" };
}

/** 받은 /api/config 응답 — 모양이 다르면 키 없음 · 데모 아님(로컬과 같은 동작) */
export function parseSiteConfig(body: unknown): SiteConfig {
  const b = (typeof body === "object" && body !== null ? body : {}) as Partial<Record<keyof SiteConfig, unknown>>;
  return { kakaoJsKey: typeof b.kakaoJsKey === "string" ? b.kakaoJsKey : "", demo: b.demo === true };
}

/** 익명에게 로그인 링크를 보일지 — 데모면 숨김, 설정을 아직 모르면(null) 숨긴 채 기다림(데모에서 잠깐 보였다 사라지지 않게) */
export function showLoginLink(authenticated: boolean, demo: boolean | null): boolean {
  return !authenticated && demo === false;
}

const ROLE_LABEL: Record<Role, string> = { ADMIN: "관리자", ANALYST: "분석가" };

/** 역할이 없을 때의 안내 — 로그인했으면 권한 부족, 익명이면 로그인 안내(데모면 조회 전용 안내) */
export function roleNotice(role: Role, authenticated: boolean, demo: boolean): string {
  if (authenticated) return `${ROLE_LABEL[role]} 권한이 필요합니다`;
  return demo ? DEMO_READ_ONLY : `${ROLE_LABEL[role]} 로그인 후 변경할 수 있습니다`;
}
