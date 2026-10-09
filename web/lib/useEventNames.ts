import { useEffect, useState } from "react";
import { fetchQuery } from "./api/client";
import { queries } from "./api/endpoints";

export type EventNames = Record<string, string>;
let shared: Promise<{ code: string; name: string }[]> | null = null;

/** 공시 유형 코드 → 이름 (seed 의 공시 분류 사전, /meta). 한 페이지에서 여러 번 써도 한 번만 받음. enabled=false 면 부르지 않음 */
export function useEventTypes(enabled = true): { code: string; name: string }[] {
  const [types, setTypes] = useState<{ code: string; name: string }[]>([]);
  useEffect(() => {
    if (!enabled) return;
    shared ??= fetchQuery(queries.meta()).then((m) => m.eventTypes.map(({ code, name }) => ({ code, name })))
      .catch(() => { shared = null; return []; });
    let live = true;
    shared.then((t) => { if (live) setTypes(t); });
    return () => { live = false; };
  }, [enabled]);
  return types;
}

export function useEventNames(enabled = true): EventNames {
  const types = useEventTypes(enabled);
  return Object.fromEntries(types.map((t) => [t.code, t.name]));
}
