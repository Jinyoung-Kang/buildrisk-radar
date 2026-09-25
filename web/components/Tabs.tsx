import { useRouter } from "next/router";
import { useRef, type KeyboardEvent } from "react";

/**
 * 접근성 탭 (role=tablist · 화살표 키) — 선택은 URL ?tab= 에 두어 링크·뒤로가기로 복원됩니다.
 * 패널 내용은 선택된 탭만 렌더 → 탭별 데이터도 그때 불러옴.
 */
export function useTab<T extends string>(tabs: readonly T[], fallback: T): [T, (t: T) => void] {
  const router = useRouter();
  const q = router.query.tab;
  const cur = (typeof q === "string" && (tabs as readonly string[]).includes(q) ? q : fallback) as T;
  const set = (t: T) => router.replace({ query: { ...router.query, tab: t } }, undefined, { shallow: true, scroll: false });
  return [cur, set];
}

export default function Tabs<T extends string>({ tabs, value, onChange, label }:
  { tabs: { id: T; label: string; badge?: number | string }[]; value: T; onChange: (t: T) => void; label: string }) {
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const onKey = (e: KeyboardEvent, i: number) => {
    const d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!d) return;
    e.preventDefault();
    const n = (i + d + tabs.length) % tabs.length;
    onChange(tabs[n].id);
    refs.current[n]?.focus();
  };
  return (
    <div role="tablist" aria-label={label} className="flex gap-1 border-b border-line mb-5 overflow-x-auto">
      {tabs.map((t, i) => {
        const on = t.id === value;
        return (
          <button key={t.id} ref={(el) => { refs.current[i] = el; }} role="tab" id={`tab-${t.id}`} aria-selected={on}
            aria-controls={`panel-${t.id}`} tabIndex={on ? 0 : -1} onClick={() => onChange(t.id)} onKeyDown={(e) => onKey(e, i)}
            className={`px-3.5 py-2 text-sm whitespace-nowrap border-b-2 -mb-px focus-ring rounded-t ${on
              ? "border-accent text-accent font-medium" : "border-transparent text-ink2 hover:text-ink"}`}>
            {t.label}{t.badge != null && t.badge !== 0 && <span className="ml-1.5 text-[11px] px-1.5 rounded-full bg-line text-ink2">{t.badge}</span>}
          </button>
        );
      })}
    </div>
  );
}

export function TabPanel({ id, children }: { id: string; children: React.ReactNode }) {
  return <div role="tabpanel" id={`panel-${id}`} aria-labelledby={`tab-${id}`}>{children}</div>;
}
