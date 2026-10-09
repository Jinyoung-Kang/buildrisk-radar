// 같은 출처 /api/v1 호출 — 화면은 이 파일을 직접 쓰지 않고 endpoints(queries · commands)와 Hook 을 거칩니다 (ADR-030)

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId?: string) { super(message); }
}

/** Spring Security 가 내려준 XSRF-TOKEN 쿠키 값 (SPA 가 읽어 헤더로 돌려보냄) */
function xsrf(): string | undefined {
  if (typeof document === "undefined") return undefined;
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
  return m ? decodeURIComponent(m[1]) : undefined;
}

export const BASE = "/api/v1";
const SAFE = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * 같은 출처 /api/v1 호출. 세션 쿠키(HttpOnly)는 브라우저가 붙이고, 변경 요청에는 CSRF 헤더를 붙입니다.
 * CSRF 토큰이 만료돼 403 CSRF_INVALID 가 오면 토큰을 새로 받아 한 번만 다시 시도합니다.
 */
/** 경로는 이 앱이 만든 '/…' 만 — 상위 경로(..)·프로토콜 상대(//)·역슬래시는 거부 (화면 파라미터가 섞여도 다른 API 로 새지 않게) */
function checkPath(path: string) {
  if (!path.startsWith("/") || path.startsWith("//") || path.includes("..") || path.includes("\\")) {
    throw new ApiError(400, "VALIDATION_ERROR", "잘못된 요청 경로입니다.");
  }
}

export async function api<T>(path: string, init?: RequestInit, retried = false): Promise<T> {
  checkPath(path);
  const method = (init?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = { "Content-Type": "application/json", ...(init?.headers as Record<string, string> || {}) };
  if (!SAFE.has(method)) { const t = xsrf(); if (t) headers["X-XSRF-TOKEN"] = t; }
  const res = await fetch(`${BASE}${path}`, { ...init, method, headers, credentials: "same-origin" });
  const text = await res.text();
  let body: any = null; // eslint-disable-line @typescript-eslint/no-explicit-any
  try { body = text ? JSON.parse(text) : null; } catch { body = null; }
  if (!res.ok) {
    if (res.status === 403 && body?.code === "CSRF_INVALID" && !retried) {
      await fetch("/api/v1/auth/me", { credentials: "same-origin" });
      return api<T>(path, init, true);
    }
    throw new ApiError(res.status, body?.code ?? "ERROR", body?.message ?? res.statusText, body?.traceId);
  }
  return body as T;
}

/** 변경 요청 (POST · PUT · PATCH · DELETE) — 로그인 세션 + CSRF */
export function mutate<T>(path: string, method: string, body?: unknown): Promise<T> {
  return api<T>(path, { method, body: body === undefined ? undefined : JSON.stringify(body) });
}

export function qs(params: Record<string, string | number | boolean | undefined | null>): string {
  const p = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => { if (v !== undefined && v !== null && v !== "") p.set(k, String(v)); });
  const s = p.toString();
  return s ? `?${s}` : "";
}

/** GET 조회 하나 — 경로와 응답 타입을 함께 묶음 (useApi 가 받아 부름) */
export type Query<T> = { readonly path: string; readonly _response?: T };

export function query<T>(path: string): Query<T> {
  return { path };
}

export function fetchQuery<T>(q: Query<T>, init?: RequestInit): Promise<T> {
  return api<T>(q.path, init);
}
