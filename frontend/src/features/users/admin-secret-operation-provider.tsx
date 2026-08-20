"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { authSession } from "@/features/auth/auth-session";
import { isApiProblemError } from "@/lib/api/problem";
import { userApi, type UserInput } from "./user-api";

type OperationKind = "create" | "reset";
type OperationResult = Promise<boolean>;
type ContextValue = {
  pending: OperationKind | null;
  error: string | null;
  traceId: string | null;
  createUser(companyCode: string, input: UserInput): OperationResult;
  resetPassword(companyCode: string, userCode: string): OperationResult;
  clearError(): void;
};

const Context = createContext<ContextValue | null>(null);

export function AdminSecretOperationProvider({ children }: { children: React.ReactNode }) {
  const [pending, setPending] = useState<OperationKind | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const [secret, setSecret] = useState("");
  const [copyMessage, setCopyMessage] = useState("");
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  const operationEpoch = useRef(0);
  const secretRef = useRef("");

  const clearSecret = useCallback(() => {
    const overwritten = secretRef.current ? "".padEnd(secretRef.current.length, "\0") : "";
    secretRef.current = overwritten;
    if (mounted.current) setSecret(overwritten);
    queueMicrotask(() => {
      secretRef.current = "";
      if (mounted.current) { setSecret(""); setCopyMessage(""); }
    });
  }, []);

  const dispose = useCallback(() => {
    operationEpoch.current += 1;
    controller.current?.abort();
    controller.current = null;
    clearSecret();
    if (mounted.current) setPending(null);
  }, [clearSecret]);

  useEffect(() => {
    mounted.current = true;
    const unsubscribe = authSession.subscribe((session) => { if (session.mode === "anonymous") dispose(); });
    return () => {
      mounted.current = false;
      unsubscribe();
      operationEpoch.current += 1;
      controller.current?.abort();
      controller.current = null;
      const overwritten = secretRef.current ? "".padEnd(secretRef.current.length, "\0") : "";
      secretRef.current = overwritten;
      secretRef.current = "";
    };
  }, [dispose]);

  const run = useCallback(async (kind: OperationKind, operation: (signal: AbortSignal) => Promise<{ temporaryPassword: string }>): OperationResult => {
    if (controller.current) return false;
    const currentController = new AbortController();
    const epoch = ++operationEpoch.current;
    controller.current = currentController;
    setPending(kind); setError(null); setTraceId(null);
    try {
      const result = await operation(currentController.signal);
      if (!mounted.current || currentController.signal.aborted || operationEpoch.current !== epoch) return false;
      secretRef.current = result.temporaryPassword;
      setSecret(result.temporaryPassword);
      return true;
    } catch (cause) {
      if (!mounted.current || currentController.signal.aborted || operationEpoch.current !== epoch) return false;
      if (isApiProblemError(cause)) { setError(cause.detail ?? cause.title); setTraceId(cause.traceId); }
      else setError(cause instanceof Error ? cause.message : "비밀 작업을 처리하지 못했습니다.");
      return false;
    } finally {
      if (mounted.current && operationEpoch.current === epoch) { controller.current = null; setPending(null); }
    }
  }, []);

  const createUser = useCallback((companyCode: string, input: UserInput) => run("create", (signal) => userApi.create(companyCode, input, signal)), [run]);
  const resetPassword = useCallback((companyCode: string, userCode: string) => run("reset", (signal) => userApi.resetPassword(companyCode, userCode, signal)), [run]);
  const clearError = useCallback(() => { setError(null); setTraceId(null); }, []);
  const value = useMemo<ContextValue>(() => ({ pending, error, traceId, createUser, resetPassword, clearError }), [clearError, createUser, error, pending, resetPassword, traceId]);

  async function copySecret() {
    try { await navigator.clipboard.writeText(secretRef.current); setCopyMessage("임시 비밀번호를 복사했습니다."); }
    catch { setCopyMessage("직접 선택해 복사해 주세요."); }
  }

  return <Context.Provider value={value}>
    {children}
    {error ? <Alert aria-live="assertive" className="fixed bottom-4 right-4 z-50 max-w-md" variant="destructive"><AlertTitle>{error}</AlertTitle><AlertDescription>{traceId ? `추적 ID: ${traceId}` : null}<Button className="mt-2" size="sm" variant="outline" onClick={clearError}>닫기</Button></AlertDescription></Alert> : null}
    <Dialog open={Boolean(secret)} onOpenChange={(open) => { if (!open) clearSecret(); }}><DialogContent showCloseButton={false}><DialogHeader><DialogTitle>일회성 임시 비밀번호</DialogTitle><DialogDescription>이 값은 지금 한 번만 표시됩니다. 닫으면 즉시 지워지며 다시 볼 수 없습니다.</DialogDescription></DialogHeader><code className="select-all rounded bg-slate-950 p-3 text-center text-white">{secret}</code><p aria-live="polite">{copyMessage}</p><DialogFooter><Button variant="outline" onClick={() => void copySecret()}>복사</Button><Button onClick={clearSecret}>비밀번호 확인 완료</Button></DialogFooter></DialogContent></Dialog>
  </Context.Provider>;
}

export function useAdminSecretOperations(): ContextValue {
  const value = useContext(Context);
  if (!value) throw new Error("useAdminSecretOperations must be used inside AdminSecretOperationProvider.");
  return value;
}
