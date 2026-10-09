import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { useAuth } from "@/lib/auth";
import { usePolling } from "@/lib/hooks/usePolling";
import { NOTIFY_EVENT } from "@/lib/notify";
import * as sessionApi from "@/lib/api/session";

/** 머리말 종 — 로그인했을 때만, 읽지 않은 알림 수를 1분마다(화면이 보일 때만) 다시 봄 (ADR-025) */
export default function NotificationBell() {
  const { me } = useAuth();
  // 숫자는 누구의 것인지와 함께 — 로그아웃하거나 다른 사람으로 로그인하면 이전 숫자를 보이지 않음(효과 안에서 지우는 대신)
  const [unread, setUnread] = useState<{ user?: string; n: number | null }>({ n: null });
  const on = !!me?.authenticated;
  const user = me?.username;
  // 마지막으로 보낸 요청의 응답만 — 먼저 보낸 요청의 늦은 응답이 새 숫자를 덮지 않게 (VERIFICATION 142)
  const seq = useRef(0);
  const load = useCallback(() => {
    if (!on || document.visibilityState !== "visible") return;
    const my = ++seq.current;
    sessionApi.unreadCount()
      .then((r) => { if (my === seq.current) setUnread({ user, n: r.unread }); })
      .catch(() => { if (my === seq.current) setUnread({ user, n: null }); });
  }, [on, user]);
  useEffect(() => {
    if (!on) return;
    load();
    window.addEventListener(NOTIFY_EVENT, load);
    return () => window.removeEventListener(NOTIFY_EVENT, load);
  }, [on, load]);
  usePolling(load, 60_000, on);   // 1분마다, 탭이 보일 때만 — 다시 보이면 곧바로
  if (!on) return null;
  const n = unread.user === user ? unread.n ?? 0 : 0;
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
