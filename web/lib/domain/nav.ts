/**
 * 로그인 뒤 돌아갈 주소 — 같은 출처의 경로만. '/\evil.example' 처럼 '/' 로 시작해도 브라우저가 프로토콜 상대 주소로 해석하므로,
 * URL 로 해석한 뒤 출처가 같은지 확인하고 경로 부분만 씁니다 (열린 리다이렉트 방지)
 */
export function safeNext(next: unknown, origin: string): string {
  if (typeof next !== "string" || !next.startsWith("/")) return "/";
  try {
    const u = new URL(next, origin);
    // 정규화하면 '/.//evil.example' 의 경로가 '//evil.example' 이 되어 같은 출처여도 프로토콜 상대 주소가 됨 (VERIFICATION 160)
    if (u.origin !== origin || /^\/[/\\]/.test(u.pathname)) return "/";
    return u.pathname + u.search + u.hash;
  } catch {
    return "/";
  }
}

/** 경보 · 알림의 대상 화면 */
export function targetHref(type: "COMPANY" | "REGION", key: string): string {
  return type === "COMPANY" ? `/companies/${key}` : `/regions/${key}`;
}

/**
 * 화면 상태의 기준 — 동적 경로의 값(/companies/[corpCode] 의 corpCode)이 바뀌면 다른 화면으로 보고 상태를 새로 시작.
 * 탭 · 필터 같은 쿼리만 바뀌면 같은 화면 (VERIFICATION 148)
 */
export function pageKey(pathname: string, query: Record<string, string | string[] | undefined>): string {
  const params = Array.from(pathname.matchAll(/\[(?:\.\.\.)?(\w+)\]/g), (m) => m[1]);
  return [pathname, ...params.map((p) => String(query[p] ?? ""))].join("|");
}

/**
 * 범위를 넘은 쪽 — 항목은 있는데(total > 0) 이 쪽이 비었으면 돌아갈 마지막 쪽 번호(0부터), 아니면 null.
 * 비었다고 '데이터 없음'을 보이면 사실과 다름(알림함 '안 읽음' 2쪽의 마지막 건을 읽은 뒤 · 범위 밖 ?p=) (VERIFICATION 200)
 */
export function pageOverflow(total: number, size: number, page: number, shown: number): number | null {
  if (shown > 0 || total <= 0 || page <= 0 || size <= 0) return null;
  const last = Math.ceil(total / size) - 1;
  return last < page ? last : null;                         // 마지막 쪽이 비었다면(데이터가 맞지 않음) 돌아갈 곳이 아님
}
