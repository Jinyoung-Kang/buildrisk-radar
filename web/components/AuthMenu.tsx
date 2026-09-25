import Link from "next/link";
import { useRouter } from "next/router";
import { useAuth } from "@/lib/auth";

export default function AuthMenu() {
  const { me, loading, logout } = useAuth();
  const router = useRouter();
  if (loading) return <span className="w-16" aria-hidden />;
  if (!me?.authenticated) {
    return (
      <Link href={`/login?next=${encodeURIComponent(router.asPath)}`}
        className="text-sm px-3 py-1.5 rounded-md border border-line hover:bg-line/60 focus-ring whitespace-nowrap">로그인</Link>
    );
  }
  return (
    <div className="flex items-center gap-2 text-sm whitespace-nowrap">
      <span className="text-ink2" title={`역할: ${me.roles.join(", ")}`}>
        {me.username} <span className="text-xs text-muted">({me.roles.includes("ADMIN") ? "관리자" : "분석가"})</span>
      </span>
      <button onClick={async () => { await logout(); router.replace(router.asPath); }}
        className="px-2 py-1 rounded-md text-ink2 hover:bg-line/60 focus-ring">로그아웃</button>
    </div>
  );
}
