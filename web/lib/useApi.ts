import { useCallback, useEffect, useRef, useState } from "react";
import { fetchQuery, type ApiError, type Query } from "./api/client";

/** GET 조회 훅 — query 가 null 이면 부르지 않음. reload() 로 다시 조회 */
export function useApi<T>(q: Query<T> | null) {
  const path = q?.path ?? null;
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<ApiError | Error | null>(null);
  const [loading, setLoading] = useState(false);
  const seq = useRef(0);
  const load = useCallback(() => {
    if (!path) return;
    const my = ++seq.current;
    setLoading(true);
    fetchQuery<T>({ path })
      .then((d) => { if (my === seq.current) { setData(d); setError(null); } })
      .catch((e) => { if (my === seq.current) setError(e); })
      .finally(() => { if (my === seq.current) setLoading(false); });
  }, [path]);
  useEffect(() => { load(); }, [load]);
  return { data, error, loading, reload: load };
}
