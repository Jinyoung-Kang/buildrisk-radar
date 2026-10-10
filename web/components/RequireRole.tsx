import Link from "next/link";
import { useRouter } from "next/router";
import type { ReactNode } from "react";
import type { Role } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { roleNotice, showLoginLink } from "@/lib/domain/demo";
import { useDemo } from "@/lib/hooks/useSiteConfig";

/** 역할이 있으면 children, 없으면 안내 한 줄 (조회는 누구나 — 버튼만 숨김). 공개 데모는 로그인 링크 대신 조회 전용 안내 */
export default function RequireRole({ role, children, inline = false }: { role: Role; children: ReactNode; inline?: boolean }) {
  const { has, loading, me } = useAuth();
  const demo = useDemo();
  const { asPath } = useRouter();
  if (loading) return null;
  if (has(role)) return <>{children}</>;
  const authenticated = !!me?.authenticated;
  if (!authenticated && demo === null) return null;
  return (
    <span className={`text-xs text-muted ${inline ? "" : "inline-flex items-center gap-2"}`}>
      🔒 {roleNotice(role, authenticated, !!demo)}
      {showLoginLink(authenticated, demo) && (
        <Link href={`/login?next=${encodeURIComponent(asPath)}`} className="text-accent hover:underline focus-ring rounded">로그인</Link>
      )}
    </span>
  );
}
