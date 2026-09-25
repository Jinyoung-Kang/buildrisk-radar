import Head from "next/head";
import Link from "next/link";
import { useRouter } from "next/router";
import { useEffect, useState, type ReactNode } from "react";
import AuthMenu from "./AuthMenu";
import ThemeToggle from "./ThemeToggle";
import { useAuth } from "@/lib/auth";

type Item = { href: string; label: string; admin?: boolean };
const GROUPS: { label: string; items: Item[] }[] = [
  { label: "모니터", items: [
    { href: "/", label: "대시보드" }, { href: "/companies", label: "기업" }, { href: "/regions", label: "지역 지도" },
    { href: "/alerts", label: "경보" }, { href: "/exposure", label: "수주·보증 노출" }, { href: "/backtest", label: "경보 검증" }] },
  { label: "운영", items: [
    { href: "/admin/rules", label: "규칙" }, { href: "/admin/batch", label: "배치" }, { href: "/admin/mapping", label: "매핑" },
    { href: "/admin/audit", label: "감사 로그", admin: true }] },
  { label: "", items: [{ href: "/about", label: "지표·출처" }] },
];

/** 모든 화면이 같은 폭(1440px)을 써서 메뉴를 바꿔도 상단 바 위치가 움직이지 않게 합니다. 1280px 미만은 메뉴 버튼. */
export default function Layout({ title, children }: { title: string; children: ReactNode; wide?: boolean }) {
  const { pathname, asPath } = useRouter();
  const { has } = useAuth();
  const [open, setOpen] = useState(false);
  useEffect(() => { setOpen(false); }, [asPath]);
  const active = (href: string) => (href === "/" ? pathname === "/" : pathname.startsWith(href));
  const groups = GROUPS.map((g) => ({ ...g, items: g.items.filter((i) => !i.admin || has("ADMIN")) }));
  const link = (n: Item, mobile = false) => (
    <Link key={n.href} href={n.href} aria-current={active(n.href) ? "page" : undefined}
      className={`${mobile ? "block px-3 py-2" : "px-2.5 py-1.5"} rounded-md whitespace-nowrap focus-ring ${active(n.href)
        ? "bg-accent/10 text-accent font-medium" : "text-ink2 hover:bg-line/60"}`}>
      {n.label}
    </Link>
  );
  return (
    <>
      <Head>
        <title>{`${title} · 건설·부동산 위험 모니터`}</title>
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <link rel="icon" href="/favicon.svg" />
      </Head>
      <a href="#main" className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 bg-raised px-3 py-2 rounded-md shadow-card">본문으로 건너뛰기</a>
      <div className="min-h-screen flex flex-col">
        <header className="sticky top-0 z-30 border-b border-line bg-surface/90 backdrop-blur">
          <div className="max-w-[1440px] mx-auto px-4 sm:px-6 h-14 flex items-center gap-4">
            <Link href="/" className="flex items-center gap-2 shrink-0 focus-ring rounded">
              <img src="/favicon.svg" alt="" className="w-6 h-6" />
              <span className="font-semibold tracking-tight">건설·부동산 위험 모니터</span>
            </Link>
            <nav className="hidden xl:flex items-center gap-0.5 text-sm flex-1 min-w-0" aria-label="주 메뉴">
              {groups.map((g, gi) => (
                <div key={gi} className={`flex items-center gap-0.5 ${gi > 0 ? "pl-2 ml-1.5 border-l border-line" : ""}`}>
                  {g.items.map((n) => link(n))}
                </div>
              ))}
            </nav>
            <div className="ml-auto flex items-center gap-2">
              <div className="hidden sm:block"><AuthMenu /></div>
              <ThemeToggle />
              <button className="xl:hidden p-2 rounded-md hover:bg-line/60 focus-ring" aria-expanded={open} aria-controls="mobile-nav"
                aria-label={open ? "메뉴 닫기" : "메뉴 열기"} onClick={() => setOpen((o) => !o)}>
                <svg width="20" height="20" viewBox="0 0 20 20" aria-hidden><path d={open ? "M5 5l10 10M15 5L5 15" : "M3 6h14M3 10h14M3 14h14"}
                  stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
              </button>
            </div>
          </div>
          {open && (
            <nav id="mobile-nav" className="xl:hidden border-t border-line bg-surface max-h-[70vh] overflow-y-auto" aria-label="주 메뉴">
              <div className="max-w-[1440px] mx-auto px-4 sm:px-6 py-3 grid sm:grid-cols-3 gap-4 text-sm">
                {groups.map((g, gi) => (
                  <div key={gi}>
                    {g.label && <div className="section-label mb-1">{g.label}</div>}
                    {g.items.map((n) => link(n, true))}
                  </div>
                ))}
                <div className="sm:hidden"><AuthMenu /></div>
              </div>
            </nav>
          )}
        </header>
        <main id="main" className="flex-1 w-full max-w-[1440px] mx-auto px-4 sm:px-6 py-6">{children}</main>
        <footer className="border-t border-line text-xs text-muted">
          <div className="max-w-[1440px] mx-auto px-4 sm:px-6 py-4 flex flex-wrap gap-x-6 gap-y-1 justify-between">
            <span>⚠ 공시·공공통계 기반 모니터링 예시, 투자 판단 자료 아님 — 투자 권유·신용평가가 아닙니다.</span>
            <span>출처: 금융감독원 Open DART · KOSIS · 한국부동산원 R-ONE · 통계청 SGIS · V-World · 국토교통부 실거래가 · 금융위원회 주식시세 · 카카오맵</span>
          </div>
        </footer>
      </div>
    </>
  );
}
