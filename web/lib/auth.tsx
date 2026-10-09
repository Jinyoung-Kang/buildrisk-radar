import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";
import { type Me, type Role } from "./api";
import * as sessionApi from "./api/session";

type Auth = {
  me: Me | null;
  loading: boolean;
  has: (role: Role) => boolean;
  login: (username: string, password: string) => Promise<Me>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
};

const ANON: Me = { authenticated: false, roles: [] };
const Ctx = createContext<Auth | null>(null);

/**
 * 로그인 상태 — 세션 쿠키는 HttpOnly 라 JS 가 읽지 못하므로 /auth/me 로 확인합니다.
 * 첫 호출이 XSRF-TOKEN 쿠키도 받아 둡니다. 비밀값(토큰·비밀번호)은 브라우저 저장소에 두지 않습니다.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);
  // 로그인 · 로그아웃 · 새 확인이 일어나면 그 전에 보낸 확인의 늦은 응답은 버림 — 화면을 열 때 보낸 익명 확인이 로그인 뒤에 도착해
  // 로그인 상태를 덮던 문제 (VERIFICATION 142)
  const version = useRef(0);
  const check = useCallback((my: number) => sessionApi.me()
    .then((m) => { if (my === version.current) setMe(m); }, () => { if (my === version.current) setMe(ANON); })
    .finally(() => { if (my === version.current) setLoading(false); }), []);
  const refresh = useCallback(() => check(++version.current), [check]);
  useEffect(() => { void check(++version.current); }, [check]);
  const login = useCallback(async (username: string, password: string) => {
    const m = await sessionApi.login(username, password);
    version.current++;
    setMe(m);
    setLoading(false);
    return m;
  }, []);
  /** 로그아웃 요청이 실패하면 서버의 실제 상태로 다시 확인 — 세션이 남았는데 로그아웃된 것처럼 보이거나 처리되지 않은 오류가 나지 않게 */
  const logout = useCallback(async () => {
    try {
      await sessionApi.logout();
      version.current++;
      setMe(ANON);
      setLoading(false);
    } catch {
      await refresh();
    }
  }, [refresh]);
  const has = useCallback((role: Role) => !!me?.roles.includes(role), [me]);
  return <Ctx.Provider value={{ me, loading, has, login, logout, refresh }}>{children}</Ctx.Provider>;
}

export function useAuth(): Auth {
  const c = useContext(Ctx);
  if (!c) throw new Error("AuthProvider 밖");
  return c;
}
