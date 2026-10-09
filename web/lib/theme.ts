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

export function applyTheme(t: Theme) {
  const el = document.documentElement;
  if (t === "system") el.removeAttribute("data-theme");
  else el.setAttribute("data-theme", t);
  try { if (t === "system") localStorage.removeItem(THEME_KEY); else localStorage.setItem(THEME_KEY, t); } catch { /* 저장 불가 환경 */ }
}
