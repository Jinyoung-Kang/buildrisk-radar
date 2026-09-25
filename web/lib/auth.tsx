import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, mutate, type Me, type Role } from "./api";

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
  const refresh = useCallback(async () => {
    try { setMe(await api<Me>("/auth/me")); } catch { setMe(ANON); } finally { setLoading(false); }
  }, []);
  useEffect(() => { refresh(); }, [refresh]);
  const login = useCallback(async (username: string, password: string) => {
    const m = await mutate<Me>("/auth/login", "POST", { username, password });
    setMe(m);
    return m;
  }, []);
  const logout = useCallback(async () => {
    try { await mutate<void>("/auth/logout", "POST"); } finally { setMe(ANON); }
  }, []);
  const has = useCallback((role: Role) => !!me?.roles.includes(role), [me]);
  return <Ctx.Provider value={{ me, loading, has, login, logout, refresh }}>{children}</Ctx.Provider>;
}

export function useAuth(): Auth {
  const c = useContext(Ctx);
  if (!c) throw new Error("AuthProvider 밖");
  return c;
}
