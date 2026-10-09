// 로그인 상태 · 머리말 알림 수 — 모든 화면이 쓰는 것만 (공통 묶음을 작게) — 화면은 `import * as sessionApi from "@/lib/api/session"` (ADR-030)
import { api, mutate } from "./client";
import type { Me } from "./types";
export const me = () => api<Me>("/auth/me");
export const login = (username: string, password: string) => mutate<Me>("/auth/login", "POST", { username, password });
export const logout = () => mutate<void>("/auth/logout", "POST");
export const unreadCount = () => api<{ unread: number }>("/me/notifications/unread-count");
