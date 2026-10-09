import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { commands } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { NOTIFY_EVENT } from "@/lib/notify";

/** 머리말 종 — 로그인했을 때만, 읽지 않은 알림 수를 1분마다(화면이 보일 때만) 다시 봄 (ADR-025) */
export default function NotificationBell() {
  const { me } = useAuth();
  const [unread, setUnread] = useState<number | null>(null);
  const on = !!me?.authenticated;
  // 마지막으로 보낸 요청의 응답만 — 먼저 보낸 요청의 늦은 응답이 새 숫자를 덮지 않게 (VERIFICATION 142)
  const seq = useRef(0);
  const load = useCallback(() => {
    if (!on || document.visibilityState !== "visible") return;
    const my = ++seq.current;
    commands.unreadCount()
      .then((r) => { if (my === seq.current) setUnread(r.unread); })
      .catch(() => { if (my === seq.current) setUnread(null); });
  }, [on]);
  useEffect(() => {
    if (!on) { setUnread(null); return; }
    load();
    const t = window.setInterval(load, 60_000);
    window.addEventListener(NOTIFY_EVENT, load);
    document.addEventListener("visibilitychange", load);
    return () => { window.clearInterval(t); window.removeEventListener(NOTIFY_EVENT, load); document.removeEventListener("visibilitychange", load); };
  }, [on, load]);
  if (!on) return null;
  const n = unread ?? 0;
  return (
    <Link href="/notifications" className="relative p-2 rounded-md hover:bg-line/60 focus-ring"
      aria-label={n > 0 ? `알림 — 읽지 않음 ${n}건` : "알림"} title="알림">
      <svg width="20" height="20" viewBox="0 0 20 20" aria-hidden fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinejoin="round">
        <path d="M10 3a5 5 0 0 0-5 5v3l-1.5 2.5h13L15 11V8a5 5 0 0 0-5-5Z" /><path d="M8 16a2 2 0 0 0 4 0" strokeLinecap="round" />
      </svg>
      {n > 0 && (
        <span className="absolute -top-0.5 -right-0.5 min-w-[1.1rem] h-[1.1rem] px-1 rounded-full bg-crit text-white text-[10px] font-semibold leading-[1.1rem] text-center tabular">
          {n > 99 ? "99+" : n}
        </span>
      )}
    </Link>
  );
}
