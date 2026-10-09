import { useEffect, useRef } from "react";

/**
 * 주기 호출 — 탭이 보이는 동안만. 숨기면 멈추고, 다시 보이면 곧바로 한 번 부른 뒤 이어감 (VERIFICATION 146).
 * 콜백은 늘 최신 것을 부르므로 렌더마다 타이머를 다시 만들지 않음
 */
export function usePolling(fn: () => void, ms: number, enabled = true) {
  const cb = useRef(fn);
  useEffect(() => { cb.current = fn; });
  useEffect(() => {
    if (!enabled) return;
    let timer: number | undefined;
    const start = () => {
      if (timer === undefined && document.visibilityState === "visible") timer = window.setInterval(() => cb.current(), ms);
    };
    const stop = () => {
      if (timer !== undefined) { window.clearInterval(timer); timer = undefined; }
    };
    const onVisibility = () => {
      if (document.visibilityState === "visible") { cb.current(); start(); } else stop();
    };
    start();
    document.addEventListener("visibilitychange", onVisibility);
    return () => { stop(); document.removeEventListener("visibilitychange", onVisibility); };
  }, [enabled, ms]);
}
