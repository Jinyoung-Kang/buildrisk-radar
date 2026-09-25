import { useRouter } from "next/router";
import { useState, type FormEvent } from "react";
import Layout from "@/components/Layout";
import { Card } from "@/components/ui";
import { ApiError } from "@/lib/api";
import { useAuth } from "@/lib/auth";

/**
 * 같은 출처 경로만 돌아감 (오픈 리다이렉트 방지). 문자열 검사만으로는 '/\\evil.example' 같은 표기를
 * 브라우저가 프로토콜 상대 주소로 해석하므로, URL 로 해석한 뒤 출처가 같은지 확인하고 경로 부분만 씁니다.
 */
function safeNext(n: unknown): string {
  if (typeof n !== "string" || !n.startsWith("/")) return "/";
  try {
    const u = new URL(n, window.location.origin);
    return u.origin === window.location.origin ? u.pathname + u.search + u.hash : "/";
  } catch {
    return "/";
  }
}

export default function Login() {
  const { login } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
      router.replace(safeNext(router.query.next));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "로그인하지 못했습니다.");
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Layout title="로그인">
      <div className="max-w-sm mx-auto mt-10">
        <Card title="로그인" sub="조회는 로그인 없이 가능합니다. 경보 확인(분석가)·규칙·배치·매핑·감사(관리자)는 로그인이 필요합니다.">
          <form onSubmit={submit} className="space-y-4" noValidate>
            <label className="block text-sm">
              <span className="text-ink2">아이디</span>
              <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required maxLength={60}
                className="mt-1 w-full bg-raised border border-line rounded-md px-3 py-2 focus-ring" autoFocus />
            </label>
            <label className="block text-sm">
              <span className="text-ink2">비밀번호</span>
              <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password"
                required maxLength={200} className="mt-1 w-full bg-raised border border-line rounded-md px-3 py-2 focus-ring" />
            </label>
            {error && <p role="alert" className="text-sm text-bad">{error}</p>}
            <button disabled={busy || !username || !password}
              className="w-full py-2 rounded-md bg-accent text-white disabled:opacity-50 focus-ring">{busy ? "확인 중…" : "로그인"}</button>
            <p className="text-xs text-muted leading-relaxed">
              계정은 서버 환경변수(.env 의 ADMIN_PASSWORD · ANALYST_PASSWORD)로만 만들어집니다. 5번 연속 틀리면 15분 동안 잠깁니다.
            </p>
          </form>
        </Card>
      </div>
    </Layout>
  );
}
