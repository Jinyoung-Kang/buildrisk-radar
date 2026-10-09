import Link from "next/link";
import { useRouter } from "next/router";
import { useEffect, useState } from "react";
import { useApi } from "@/lib/hooks/useApi";
import * as insightApi from "@/lib/api/insight";

type Kind = "company" | "region";

/** 이름 찾기 — 입력이 멈추면(200 ms) /search 를 부르고, Enter 는 첫 결과로 */
export default function EntitySearch({ id, label, placeholder, kinds }: { id: string; label: string; placeholder: string; kinds: Kind[] }) {
  const router = useRouter();
  const [q, setQ] = useState("");
  const [dq, setDq] = useState("");
  useEffect(() => { const t = setTimeout(() => setDq(q.trim()), 200); return () => clearTimeout(t); }, [q]);
  const { data } = useApi(dq ? insightApi.search(dq) : null);
  const fresh = !!dq && data?.q === dq;
  const hits = !fresh || !data ? [] : kinds.flatMap((k) => k === "company"
    ? data.companies.map((c) => ({ href: `/companies/${c.key}`, name: c.name, kind: "기업" }))
    : data.regions.map((r) => ({ href: `/regions/${r.key}`, name: r.name, kind: "지역" })));
  return (
    <form role="search" aria-label={label} onSubmit={(e) => { e.preventDefault(); if (hits[0]) router.push(hits[0].href); }}>
      <label htmlFor={id} className="sr-only">{label}</label>
      <input id={id} type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder={placeholder} maxLength={40} autoComplete="off"
        className="w-full rounded-lg border border-line bg-page px-3 py-1.5 text-sm placeholder:text-muted focus-ring" />
      {fresh && (hits.length === 0 ? <p className="text-xs text-muted mt-1.5 px-1">찾는 이름이 없습니다.</p> : (
        <ul className="mt-1.5 rounded-lg border border-line divide-y divide-line/60" aria-label={`${label} 결과`}>
          {hits.map((h) => (
            <li key={h.href}>
              <Link href={h.href} className="flex items-center justify-between gap-2 px-3 py-1.5 text-sm hover:bg-line/40 focus-ring rounded-lg">
                <span className="truncate">{h.name}</span><span className="text-[11px] text-muted shrink-0">{h.kind}</span>
              </Link>
            </li>))}
        </ul>
      ))}
    </form>
  );
}
