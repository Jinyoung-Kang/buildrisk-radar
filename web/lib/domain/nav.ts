/**
 * 로그인 뒤 돌아갈 주소 — 같은 출처의 경로만. '/\evil.example' 처럼 '/' 로 시작해도 브라우저가 프로토콜 상대 주소로 해석하므로,
 * URL 로 해석한 뒤 출처가 같은지 확인하고 경로 부분만 씁니다 (열린 리다이렉트 방지)
 */
export function safeNext(next: unknown, origin: string): string {
  if (typeof next !== "string" || !next.startsWith("/")) return "/";
  try {
    const u = new URL(next, origin);
    return u.origin === origin ? u.pathname + u.search + u.hash : "/";
  } catch {
    return "/";
  }
}

/** 경보 · 알림의 대상 화면 */
export function targetHref(type: "COMPANY" | "REGION", key: string): string {
  return type === "COMPANY" ? `/companies/${key}` : `/regions/${key}`;
}
