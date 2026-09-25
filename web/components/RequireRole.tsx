import Link from "next/link";
import { useRouter } from "next/router";
import type { ReactNode } from "react";
import type { Role } from "@/lib/api";
import { useAuth } from "@/lib/auth";

const ROLE_LABEL: Record<Role, string> = { ADMIN: "관리자", ANALYST: "분석가" };

/** 역할이 있으면 children, 없으면 안내 한 줄 (조회는 누구나 — 버튼만 숨김) */
export default function RequireRole({ role, children, inline = false }: { role: Role; children: ReactNode; inline?: boolean }) {
  const { has, loading, me } = useAuth();
  const { asPath } = useRouter();
  if (loading) return null;
  if (has(role)) return <>{children}</>;
  const text = me?.authenticated ? `${ROLE_LABEL[role]} 권한이 필요합니다` : `${ROLE_LABEL[role]} 로그인 후 변경할 수 있습니다`;
  return (
    <span className={`text-xs text-muted ${inline ? "" : "inline-flex items-center gap-2"}`}>
      🔒 {text}
      {!me?.authenticated && (
        <Link href={`/login?next=${encodeURIComponent(asPath)}`} className="text-accent hover:underline focus-ring rounded">로그인</Link>
      )}
    </span>
  );
}
