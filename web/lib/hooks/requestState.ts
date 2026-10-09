// useApi 의 상태 전이 — 응답을 요청한 경로에 묶음. React 없이 시험할 수 있게 순수 함수로 둠

export type RequestState<T> = { path: string | null; data: T | null; error: Error | null; reloading: boolean };

export const EMPTY: RequestState<never> = { path: null, data: null, error: null, reloading: false };

export function resolved<T>(path: string, data: T): RequestState<T> {
  return { path, data, error: null, reloading: false };
}

/** 실패 — 같은 경로를 다시 불러오다 실패하면 이전 값은 남기고, 다른 경로의 값은 버림 */
export function rejected<T>(s: RequestState<T>, path: string, error: Error): RequestState<T> {
  return { path, data: s.path === path ? s.data : null, error, reloading: false };
}

export function reloading<T>(s: RequestState<T>): RequestState<T> {
  return { ...s, reloading: true };
}

/**
 * data 는 지금 경로의 값만 — 조건을 바꾼 직후 이전 조건의 응답이 새 조건의 이름표 옆에 보이지 않게 (VERIFICATION 140).
 * 같은 경로를 다시 불러오는 동안에는 이전 값을 그대로 보여 줌(깜빡임 없음).
 * stale 은 다른 경로(이전 조건)의 마지막 값 — 새 값을 기다리는 동안 이전 화면을 흐리게 남기려는 곳만, 이름표도 그 값에서 읽어 씀
 */
export function view<T>(s: RequestState<T>, path: string | null): { data: T | null; stale: T | null; error: Error | null; loading: boolean } {
  const mine = path !== null && s.path === path;
  return { data: mine ? s.data : null, stale: mine ? null : s.data, error: mine ? s.error : null, loading: path !== null && (!mine || s.reloading) };
}
