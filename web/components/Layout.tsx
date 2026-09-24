import Head from "next/head";
import Link from "next/link";
import { useRouter } from "next/router";
import type { ReactNode } from "react";
import ThemeToggle from "./ThemeToggle";

const NAV = [
  { href: "/", label: "대시보드" },
  { href: "/companies", label: "기업" },
  { href: "/regions", label: "지역 지도" },
  { href: "/alerts", label: "경보" },
  { href: "/admin/rules", label: "규칙" },
  { href: "/admin/batch", label: "배치 모니터" },
  { href: "/admin/mapping", label: "매핑" },
  { href: "/about", label: "지표·출처" },
];

/** 모든 화면이 같은 폭(1440px)을 써서 메뉴를 바꿔도 상단 바 위치가 움직이지 않게 합니다. */
export default function Layout({ title, children }: { title: string; children: ReactNode; wide?: boolean }) {
  const { pathname } = useRouter();
  const active = (href: string) => (href === "/" ? pathname === "/" : pathname.startsWith(href));
  return (
    <>
      <Head>
        <title>{`${title} · 건설·부동산 위험 모니터`}</title>
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <link rel="icon" href="/favicon.svg" />
      </Head>
      <div className="min-h-screen flex flex-col">
        <header className="sticky top-0 z-30 border-b border-line bg-surface/90 backdrop-blur">
          <div className={`max-w-[1440px] mx-auto px-6 h-14 flex items-center gap-6`}>
            <Link href="/" className="flex items-center gap-2 shrink-0 focus-ring rounded">
              <img src="/favicon.svg" alt="" className="w-6 h-6" />
              <span className="font-semibold tracking-tight">건설·부동산 위험 모니터</span>
            </Link>
            <nav className="flex gap-1 overflow-x-auto text-sm flex-1" aria-label="주 메뉴">
              {NAV.map((n) => (
                <Link key={n.href} href={n.href} aria-current={active(n.href) ? "page" : undefined}
                  className={`px-3 py-1.5 rounded-md whitespace-nowrap focus-ring ${active(n.href)
                    ? "bg-accent/10 text-accent font-medium" : "text-ink2 hover:bg-line/60"}`}>
                  {n.label}
                </Link>
              ))}
            </nav>
            <ThemeToggle />
          </div>
        </header>
        <main className={`flex-1 w-full max-w-[1440px] mx-auto px-6 py-6`}>{children}</main>
        <footer className="border-t border-line text-xs text-muted">
          <div className="max-w-[1440px] mx-auto px-6 py-4 flex flex-wrap gap-x-6 gap-y-1 justify-between">
            <span>⚠ 공시·공공통계 기반 모니터링 예시, 투자 판단 자료 아님 — 투자 권유·신용평가가 아닙니다.</span>
            <span>출처: 금융감독원 Open DART · KOSIS · 한국부동산원 R-ONE · 통계청 SGIS · V-World · 카카오맵</span>
          </div>
        </footer>
      </div>
    </>
  );
}
