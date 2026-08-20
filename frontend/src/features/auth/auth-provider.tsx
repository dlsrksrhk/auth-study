"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";

import { authApi, type Actor } from "./auth-api";
import { authSession } from "./auth-session";

export type AuthStatus =
  | "loading"
  | "authenticated"
  | "passwordChangeRequired"
  | "anonymous";

type LoginResult = { mustChangePassword: boolean };

type AuthContextValue = {
  status: AuthStatus;
  actor: Actor | null;
  login(email: string, password: string): Promise<LoginResult>;
  logout(): Promise<void>;
  changePassword(currentPassword: string, newPassword: string): Promise<void>;
};

type RestoredAuth = { status: "authenticated"; actor: Actor } | { status: "anonymous"; actor: null };

const AuthContext = createContext<AuthContextValue | null>(null);
let restoration: Promise<RestoredAuth> | null = null;
let restoredActor: Actor | null = null;

authSession.subscribe((session) => {
  if (session.mode === "anonymous") {
    restoration = null;
    restoredActor = null;
  }
});

function restoreOnce(): Promise<RestoredAuth> {
  if (authSession.get().mode === "authenticated" && restoredActor) {
    return Promise.resolve({ status: "authenticated", actor: restoredActor });
  }
  if (!restoration) {
    restoration = (async () => {
      try {
        await authApi.refresh();
        const actor = await authApi.me();
        restoredActor = actor;
        return { status: "authenticated" as const, actor };
      } catch {
        authSession.clear();
        return { status: "anonymous" as const, actor: null };
      }
    })();
  }
  return restoration;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const initialSession = authSession.get();
  const initialMode = useRef(initialSession.mode);
  const [status, setStatus] = useState<AuthStatus>(() =>
    initialSession.mode === "passwordChangeRequired"
      ? "passwordChangeRequired"
      : initialSession.mode === "authenticated" && restoredActor
        ? "authenticated"
        : "loading",
  );
  const [actor, setActor] = useState<Actor | null>(restoredActor);

  useEffect(() => {
    let active = true;
    const unsubscribe = authSession.subscribe((nextSession) => {
      if (!active || nextSession.mode !== "anonymous") return;
      setActor(null);
      setStatus("anonymous");
    });

    if (initialMode.current !== "passwordChangeRequired") {
      void restoreOnce().then((restored) => {
        if (!active) return;
        setActor(restored.actor);
        setStatus(restored.status);
      });
    }

    return () => {
      active = false;
      unsubscribe();
    };
  }, []);

  const login = useCallback(async (email: string, password: string): Promise<LoginResult> => {
    const token = await authApi.login(email, password);
    if (token.mustChangePassword) {
      restoredActor = null;
      authSession.set(token.accessToken, "passwordChangeRequired");
      setActor(null);
      setStatus("passwordChangeRequired");
      return { mustChangePassword: true };
    }

    authSession.set(token.accessToken, "authenticated");
    const currentActor = await authApi.me();
    restoredActor = currentActor;
    setActor(currentActor);
    setStatus("authenticated");
    return { mustChangePassword: false };
  }, []);

  const logout = useCallback(async () => {
    const serverLogout = authApi.logout();
    authSession.clear();
    setActor(null);
    setStatus("anonymous");
    try {
      await serverLogout;
    } catch {
      // Local sign-out must not depend on the server being available.
    }
  }, []);

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    await authApi.changePassword(currentPassword, newPassword);
    authSession.clear();
    setActor(null);
    setStatus("anonymous");
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ status, actor, login, logout, changePassword }),
    [actor, changePassword, login, logout, status],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider.");
  return value;
}
