import { useCallback, useEffect, useRef, useState } from "react";
import { fetchQuery, type Query } from "../api/client";
import { EMPTY, rejected, reloading, resolved, view, type RequestState } from "./requestState";

/**
 * GET 조회 훅 — query 가 null 이면 부르지 않음. reload() 로 다시 조회.
 * 응답은 요청한 경로에 묶여, 경로가 바뀌면 새 응답이 올 때까지 data 는 null(이전 조건의 숫자를 내보내지 않음).
 * 경로가 바뀌거나 화면을 떠나면 진행 중인 요청을 취소하고, 늦게 온 응답은 버림
 */
export function useApi<T>(q: Query<T> | null) {
  const path = q?.path ?? null;
  const [state, setState] = useState<RequestState<T>>(EMPTY);
  const inflight = useRef<AbortController | null>(null);
  const start = useCallback((p: string) => {
    inflight.current?.abort();
    const c = new AbortController();
    inflight.current = c;
    fetchQuery<T>({ path: p }, { signal: c.signal })
      .then((d) => { if (!c.signal.aborted) setState(resolved(p, d)); })
      .catch((e: unknown) => { if (!c.signal.aborted) setState((s) => rejected(s, p, e instanceof Error ? e : new Error(String(e)))); });
  }, []);
  useEffect(() => {
    if (!path) return;
    start(path);
    return () => inflight.current?.abort();
  }, [path, start]);
  const reload = useCallback(() => {
    if (!path) return;
    setState(reloading);
    start(path);
  }, [path, start]);
  return { ...view(state, path), reload };
}
