"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { api, tokenStore } from "./api";
import type { LoginResponse, Profile } from "./types";

type AuthState = {
  profile: Profile | null;
  ready: boolean;
  login: (email: string, password: string) => Promise<void>;
  signup: (email: string, password: string, nickname: string) => Promise<void>;
  logout: () => Promise<void>;
  setProfile: (p: Profile) => void;
};

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [profile, setProfile] = useState<Profile | null>(null);
  const [ready, setReady] = useState(false);

  const loadProfile = useCallback(async () => {
    const token = tokenStore.get();
    if (!token) return null;
    try {
      return await api<Profile>("/api/me/profile");
    } catch {
      tokenStore.clear();
      return null;
    }
  }, []);

  useEffect(() => {
    loadProfile().then((p) => {
      setProfile(p);
      setReady(true);
    });
  }, [loadProfile]);

  const login = useCallback(
    async (email: string, password: string) => {
      const res = await api<LoginResponse>("/api/auth/login", { method: "POST", body: { email, password } });
      tokenStore.set(res.accessToken);
      setProfile(await loadProfile());
    },
    [loadProfile],
  );

  const signup = useCallback(
    async (email: string, password: string, nickname: string) => {
      await api<{ memberId: number }>("/api/auth/signup", { method: "POST", body: { email, password, nickname } });
      await login(email, password);
    },
    [login],
  );

  const logout = useCallback(async () => {
    try {
      await api("/api/auth/logout", { method: "POST" });
    } catch {
      /* 서버가 없어도 로컬 상태는 지운다 */
    }
    tokenStore.clear();
    setProfile(null);
  }, []);

  return (
    <AuthContext.Provider value={{ profile, ready, login, signup, logout, setProfile }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("AuthProvider 밖에서 useAuth를 썼습니다.");
  return ctx;
}
