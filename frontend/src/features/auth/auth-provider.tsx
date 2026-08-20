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

import { StaleAuthOperationError } from "@/lib/api/client";
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

type RestoredAuth =
  | { status: "authenticated"; actor: Actor; generation: number }
  | { status: "anonymous"; actor: null; generation: number };

type CachedActor = { actor: Actor; generation: number };

const AuthContext = createContext<AuthContextValue | null>(null);
let restoration: Promise<RestoredAuth> | null = null;
let restoredActor: CachedActor | null = null;

authSession.subscribe((session) => {
  if (restoredActor && restoredActor.generation !== session.generation) restoredActor = null;
  if (session.mode === "anonymous") restoration = null;
});

function restoreOnce(): Promise<RestoredAuth> {
  const current = authSession.get();
  if (
    current.mode === "authenticated" &&
    restoredActor?.generation === current.generation
  ) {
    return Promise.resolve({
      status: "authenticated",
      actor: restoredActor.actor,
      generation: current.generation,
    });
  }
  if (!restoration) {
    restoration = (async () => {
      try {
        const refreshed = await authApi.refresh();
        try {
          const actor = await authApi.me(refreshed.token.accessToken);
          return {
            status: "authenticated" as const,
            actor,
            generation: refreshed.session.generation,
          };
        } catch (error) {
          authSession.clearIfCurrent(refreshed.session.generation);
          throw error;
        }
      } catch {
        const anonymous = authSession.get();
        return { status: "anonymous" as const, actor: null, generation: anonymous.generation };
      }
    })();
  }
  return restoration;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const initialSession = authSession.get();
  const initialMode = useRef(initialSession.mode);
  const operationEpoch = useRef(0);
  const initialActor =
    initialSession.mode === "authenticated" &&
    restoredActor?.generation === initialSession.generation
      ? restoredActor.actor
      : null;
  const [status, setStatus] = useState<AuthStatus>(() =>
    initialSession.mode === "passwordChangeRequired"
      ? "passwordChangeRequired"
      : initialActor
        ? "authenticated"
        : "loading",
  );
  const [actor, setActor] = useState<Actor | null>(initialActor);

  useEffect(() => {
    let active = true;
    const operation = ++operationEpoch.current;
    const unsubscribe = authSession.subscribe((nextSession) => {
      if (!active || nextSession.mode !== "anonymous") return;
      setActor(null);
      setStatus("anonymous");
    });

    if (initialMode.current !== "passwordChangeRequired") {
      void restoreOnce().then((restored) => {
        if (
          !active ||
          operationEpoch.current !== operation ||
          !authSession.isCurrent(restored.generation)
        ) return;
        if (restored.status === "authenticated") {
          restoredActor = { actor: restored.actor, generation: restored.generation };
        }
        setActor(restored.actor);
        setStatus(restored.status);
      });
    }

    return () => {
      active = false;
      if (operationEpoch.current === operation) operationEpoch.current += 1;
      unsubscribe();
    };
  }, []);

  const login = useCallback(async (email: string, password: string): Promise<LoginResult> => {
    const safeAnonymous = authSession.clear();
    const operation = ++operationEpoch.current;
    restoredActor = null;
    setActor(null);
    setStatus("loading");
    try {
      const token = await authApi.login(email, password);
      assertCurrent(operationEpoch.current, operation);

      if (token.mustChangePassword) {
        const committed = authSession.compareAndSet(
          safeAnonymous.generation,
          token.accessToken,
          "passwordChangeRequired",
          "interactive",
        );
        if (!committed) throw new StaleAuthOperationError();
        setStatus("passwordChangeRequired");
        return { mustChangePassword: true };
      }

      const currentActor = await authApi.me(token.accessToken);
      assertCurrent(operationEpoch.current, operation);
      const committed = authSession.compareAndSet(
        safeAnonymous.generation,
        token.accessToken,
        "authenticated",
        "interactive",
      );
      if (!committed) throw new StaleAuthOperationError();
      restoredActor = { actor: currentActor, generation: committed.generation };
      setActor(currentActor);
      setStatus("authenticated");
      return { mustChangePassword: false };
    } catch (error) {
      if (operationEpoch.current === operation) {
        authSession.clearIfCurrent(safeAnonymous.generation);
        setActor(null);
        setStatus("anonymous");
      }
      throw error;
    }
  }, []);

  const logout = useCallback(async () => {
    const operation = ++operationEpoch.current;
    const previous = authSession.get();
    const cleared = authSession.clear();
    restoredActor = null;
    setActor(null);
    setStatus("anonymous");
    try {
      await authApi.logout(previous.accessToken);
    } catch {
      // Local sign-out must not depend on the server being available.
    } finally {
      if (operationEpoch.current === operation) {
        authSession.clearIfCurrent(cleared.generation);
        setActor(null);
        setStatus("anonymous");
      }
    }
  }, []);

  const changePassword = useCallback(async (currentPassword: string, newPassword: string) => {
    const operation = ++operationEpoch.current;
    const expectedSession = authSession.get();
    await authApi.changePassword(currentPassword, newPassword);
    assertCurrent(operationEpoch.current, operation);
    if (!authSession.clearIfCurrent(expectedSession.generation)) {
      throw new StaleAuthOperationError();
    }
    restoredActor = null;
    setActor(null);
    setStatus("anonymous");
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ status, actor, login, logout, changePassword }),
    [actor, changePassword, login, logout, status],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

function assertCurrent(currentOperation: number, expectedOperation: number): void {
  if (currentOperation !== expectedOperation) throw new StaleAuthOperationError();
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) throw new Error("useAuth must be used inside AuthProvider.");
  return value;
}
