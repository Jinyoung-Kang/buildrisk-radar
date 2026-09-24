// 화면 테마: system(OS 설정) · light · dark. 선택은 이 브라우저 localStorage 에만 저장합니다.
export type Theme = "system" | "light" | "dark";
export const THEME_KEY = "br-theme";

export function readTheme(): Theme {
  try {
    const v = localStorage.getItem(THEME_KEY);
    return v === "light" || v === "dark" ? v : "system";
  } catch { return "system"; }
}

export function applyTheme(t: Theme) {
  const el = document.documentElement;
  if (t === "system") el.removeAttribute("data-theme");
  else el.setAttribute("data-theme", t);
  try { if (t === "system") localStorage.removeItem(THEME_KEY); else localStorage.setItem(THEME_KEY, t); } catch { /* 저장 불가 환경 */ }
}

/** 첫 페인트 전에 실행 — 저장된 테마를 먼저 적용해 깜빡임 방지 (_document 에 인라인) */
export const THEME_BOOT = `try{var t=localStorage.getItem("${THEME_KEY}");if(t==="light"||t==="dark")document.documentElement.setAttribute("data-theme",t)}catch(e){}`;
