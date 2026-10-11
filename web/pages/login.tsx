import { useRouter } from "next/router";
import { useState, type FormEvent } from "react";
import Layout from "@/components/Layout";
import { Card, Prose } from "@/components/ui";
import { ApiError } from "@/lib/api";
import { safeNext } from "@/lib/domain/nav";
import { useAuth } from "@/lib/auth";
import { useDemo } from "@/lib/hooks/useSiteConfig";

export default function Login() {
  const { login } = useAuth();
  const router = useRouter();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const demo = useDemo();

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username.trim(), password);
      router.replace(safeNext(router.query.next, window.location.origin));
    } catch (err) {
      setError(err instanceof ApiError && err.message ? err.message : "로그인하지 못했습니다.");
      setPassword("");
    } finally {
      setBusy(false);
    }
  }

  return (
    <Layout title="로그인">
      <div className="max-w-sm mx-auto mt-10">
        <Card as="h1" title="로그인" sub="조회는 로그인 없이 가능합니다. 경보 확인(분석가)·규칙·배치·매핑·감사(관리자)는 로그인이 필요합니다.">
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
            {demo && <p className="text-xs text-muted"><Prose text="공개 데모 주소에서는 조회만 됩니다(로그인 요청은 앞단에서 막힘). 로그인은 관리 주소에서만 됩니다." /></p>}
            <button disabled={busy || !username || !password}
              className="w-full py-2 rounded-md bg-accent text-white disabled:opacity-50 focus-ring">{busy ? "확인 중…" : "로그인"}</button>
            <p className="text-xs text-muted leading-relaxed">
              <Prose text="계정은 서버 환경변수(.env 의 ADMIN_PASSWORD · ANALYST_PASSWORD)로만 만들어집니다. 5번 연속 틀리면 15분 동안 잠깁니다." />
            </p>
          </form>
        </Card>
      </div>
    </Layout>
  );
}
