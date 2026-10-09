import { commands, queries } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useAction } from "@/lib/hooks/useAction";
import { useApi } from "@/lib/hooks/useApi";

/** 관심 목록 넣기 · 빼기 (로그인했을 때만). '관심 목록만' 구독이 이 목록으로 알림을 거름 (ADR-025) */
export default function WatchButton({ type, keyCd }: { type: "COMPANY" | "REGION"; keyCd: string }) {
  const { me } = useAuth();
  const { data, reload } = useApi(me?.authenticated ? queries.watches() : null);
  const action = useAction();
  if (!me?.authenticated || !data) return null;
  const on = data.some((w) => w.targetType === type && w.targetKey === keyCd);
  const toggle = () => action.run(() => (on ? commands.unwatch(type, keyCd) : commands.watch(type, keyCd)), reload);
  return (
    <span className="inline-flex items-center gap-2">
      <button type="button" onClick={toggle} disabled={action.busy} aria-pressed={on}
        className={`text-xs px-2 py-1 rounded-md border focus-ring whitespace-nowrap ${on ? "border-accent text-accent" : "border-line text-ink2 hover:bg-line/60"}`}>
        <span aria-hidden>{on ? "★" : "☆"}</span> {on ? "관심 목록에 있음" : "관심 목록에 넣기"}
      </button>
      {action.error && <span className="text-xs text-crit" role="alert">{action.error.message}</span>}
    </span>
  );
}
