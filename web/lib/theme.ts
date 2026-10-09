// 화면 테마: system(OS 설정) · light · dark. 선택은 이 브라우저 localStorage 에만 저장합니다.
export type Theme = "system" | "light" | "dark";
/** 첫 페인트 전에 테마를 적용하는 public/theme-boot.js 도 같은 키를 씀 (CSP 때문에 인라인 대신 정적 파일) */
export const THEME_KEY = "br-theme";

export function readTheme(): Theme {
  try {
    const v = localStorage.getItem(THEME_KEY);
    return v === "light" || v === "dark" ? v : "system";
  } catch { return "system"; }
}

const CHANGED = "br:theme-changed";

/** 테마가 바뀌면(이 탭에서 고름 · 다른 탭의 저장) 알림 — useSyncExternalStore 용 */
export function subscribeTheme(onChange: () => void): () => void {
  window.addEventListener(CHANGED, onChange);
  window.addEventListener("storage", onChange);
  return () => { window.removeEventListener(CHANGED, onChange); window.removeEventListener("storage", onChange); };
}

export function applyTheme(t: Theme) {
  const el = document.documentElement;
  if (t === "system") el.removeAttribute("data-theme");
  else el.setAttribute("data-theme", t);
  try { if (t === "system") localStorage.removeItem(THEME_KEY); else localStorage.setItem(THEME_KEY, t); } catch { /* 저장 불가 환경 */ }
  window.dispatchEvent(new Event(CHANGED));
}
