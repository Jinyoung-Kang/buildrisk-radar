import { useEffect, useState } from "react";
import { applyTheme, readTheme, type Theme } from "@/lib/theme";

const OPTIONS: { value: Theme; label: string; icon: string }[] = [
  { value: "system", label: "시스템 설정", icon: "◐" },
  { value: "light", label: "라이트 모드", icon: "☀" },
  { value: "dark", label: "다크 모드", icon: "☾" },
];

/** 상단 바 오른쪽 테마 전환 — 시스템 · 라이트 · 다크 */
export default function ThemeToggle() {
  const [theme, setTheme] = useState<Theme>("system");
  useEffect(() => { setTheme(readTheme()); }, []);
  return (
    <div role="radiogroup" aria-label="화면 테마" className="inline-flex bg-line/60 rounded-lg p-0.5 text-sm shrink-0">
      {OPTIONS.map((o) => (
        <button key={o.value} role="radio" aria-checked={theme === o.value} title={o.label} aria-label={o.label}
          onClick={() => { applyTheme(o.value); setTheme(o.value); }}
          className={`w-8 h-7 rounded-md focus-ring leading-none ${theme === o.value ? "bg-raised shadow-card text-ink" : "text-muted hover:text-ink"}`}>
          {o.icon}
        </button>
      ))}
    </div>
  );
}
