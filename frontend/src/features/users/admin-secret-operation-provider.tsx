"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { authSession } from "@/features/auth/auth-session";
import { isApiProblemError } from "@/lib/api/problem";
import { resourceCode } from "@/lib/resource-code";
import { userApi, type UserInput } from "./user-api";

export type SecretOperationKind = "CREATE" | "RESET";
export type SecretOperation = {
  id: number;
  kind: SecretOperationKind;
  companyCode: string;
  userCode: string;
  loginEmail?: string;
};
type Secret = SecretOperation & { value: string };
type OperationResult = Promise<boolean>;
type ContextValue = {
  pending: SecretOperation | null;
  error: string | null;
  traceId: string | null;
  createUser(companyCode: string, input: UserInput): OperationResult;
  resetPassword(companyCode: string, userCode: string, loginEmail?: string): OperationResult;
  clearError(): void;
};
type OperationTarget = Omit<SecretOperation, "id">;
type SecretResult = { temporaryPassword: string };
type RunOperation = <T extends SecretResult>(target: OperationTarget, operation: (signal: AbortSignal) => Promise<T>, resultTarget?: (result: T) => OperationTarget) => OperationResult;

const Context = createContext<ContextValue | null>(null);

export function AdminSecretOperationProvider({ children }: { children: React.ReactNode }) {
  const [pending, setPending] = useState<SecretOperation | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const [secret, setSecret] = useState<Secret | null>(null);
  const [copyMessage, setCopyMessage] = useState("");
  const mounted = useRef(true);
  const controller = useRef<AbortController | null>(null);
  const operationEpoch = useRef(0);
  const secretRef = useRef<Secret | null>(null);

  const clearSecret = useCallback(() => {
    const current = secretRef.current;
    setCopyMessage("");
    if (!current) { if (mounted.current) setSecret(null); return; }
    const overwritten = { ...current, value: "".padEnd(current.value.length, "\0") };
    secretRef.current = overwritten;
    if (mounted.current) setSecret(overwritten);
    queueMicrotask(() => {
      if (secretRef.current?.id !== current.id || secretRef.current.value !== overwritten.value) return;
      secretRef.current = null;
      if (mounted.current) setSecret(null);
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
      const current = secretRef.current;
      if (current) secretRef.current = { ...current, value: "".padEnd(current.value.length, "\0") };
      secretRef.current = null;
    };
  }, [dispose]);

  const run = useCallback<RunOperation>(async (target, operation, resultTarget) => {
    if (controller.current) return false;
    const currentController = new AbortController();
    const epoch = ++operationEpoch.current;
    const currentOperation = { id: epoch, ...target };
    controller.current = currentController;
    setPending(currentOperation); setError(null); setTraceId(null);
    try {
      const result = await operation(currentController.signal);
      if (!mounted.current || currentController.signal.aborted || operationEpoch.current !== epoch) return false;
      const resolvedTarget = resultTarget?.(result) ?? target;
      const nextSecret = { id: epoch, ...resolvedTarget, value: result.temporaryPassword };
      secretRef.current = nextSecret;
      setCopyMessage("");
      setSecret(nextSecret);
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

  const createUser = useCallback((companyCode: string, input: UserInput) => run({ kind: "CREATE", companyCode: displayCode(companyCode, "회사 코드"), userCode: displayCode(input.code, "사용자 코드"), loginEmail: input.loginEmail.trim().toLowerCase() }, (signal) => userApi.create(companyCode, input, signal), (result) => ({ kind: "CREATE", companyCode: displayCode(companyCode, "회사 코드"), userCode: result.user.code, loginEmail: result.user.loginEmail })), [run]);
  const resetPassword = useCallback((companyCode: string, userCode: string, loginEmail?: string) => run({ kind: "RESET", companyCode: displayCode(companyCode, "회사 코드"), userCode: displayCode(userCode, "사용자 코드"), loginEmail }, (signal) => userApi.resetPassword(companyCode, userCode, signal)), [run]);
  const clearError = useCallback(() => { setError(null); setTraceId(null); }, []);
  const value = useMemo<ContextValue>(() => ({ pending, error, traceId, createUser, resetPassword, clearError }), [clearError, createUser, error, pending, resetPassword, traceId]);

  async function copySecret() {
    const current = secretRef.current;
    if (!current) return;
    try {
      await navigator.clipboard.writeText(current.value);
      if (mounted.current && secretRef.current?.id === current.id && secretRef.current.value === current.value) setCopyMessage("임시 비밀번호를 복사했습니다.");
    } catch {
      if (mounted.current && secretRef.current?.id === current.id && secretRef.current.value === current.value) setCopyMessage("직접 선택해 복사해 주세요.");
    }
  }

  const secretVisible = Boolean(secret?.value && !/^\0+$/.test(secret.value));

  return <Context.Provider value={value}>
    {children}
    {pending ? <p aria-live="polite" className="fixed bottom-4 left-4 z-50 max-w-md rounded-lg bg-slate-950 px-4 py-3 text-sm text-white" role="status">{pending.kind === "CREATE" ? "사용자 생성" : "임시 비밀번호 재발급"} 작업을 처리 중입니다. 다른 일회성 비밀번호 작업은 완료 후 시작할 수 있습니다. {pending.companyCode} / {pending.userCode}{pending.loginEmail ? ` / ${pending.loginEmail}` : ""}</p> : null}
    {error ? <Alert aria-live="assertive" className="fixed bottom-4 right-4 z-50 max-w-md" variant="destructive"><AlertTitle>{error}</AlertTitle><AlertDescription>{traceId ? `추적 ID: ${traceId}` : null}<Button className="mt-2" size="sm" variant="outline" onClick={clearError}>닫기</Button></AlertDescription></Alert> : null}
    <Dialog open={secretVisible} onOpenChange={(open) => { if (!open) clearSecret(); }}><DialogContent showCloseButton={false}><DialogHeader><DialogTitle>{secret?.kind === "CREATE" ? "사용자 생성 임시 비밀번호" : "임시 비밀번호 재발급"}</DialogTitle><DialogDescription>이 값은 지금 한 번만 표시됩니다. 닫으면 즉시 지워지며 다시 볼 수 없습니다.</DialogDescription></DialogHeader>{secret ? <dl className="grid grid-cols-[6rem_1fr] gap-2 text-sm"><dt className="text-slate-500">회사 코드</dt><dd className="font-mono">{secret.companyCode}</dd><dt className="text-slate-500">사용자 코드</dt><dd className="font-mono">{secret.userCode}</dd>{secret.loginEmail ? <><dt className="text-slate-500">로그인 이메일</dt><dd className="break-all">{secret.loginEmail}</dd></> : null}</dl> : null}<code className="select-all rounded bg-slate-950 p-3 text-center text-white">{secret?.value}</code><p aria-live="polite">{copyMessage}</p><DialogFooter><Button variant="outline" onClick={() => void copySecret()}>복사</Button><Button onClick={clearSecret}>비밀번호 확인 완료</Button></DialogFooter></DialogContent></Dialog>
  </Context.Provider>;
}

function displayCode(value: string, label: string) {
  try { return decodeURIComponent(resourceCode(value, label)); }
  catch { return value.trim().toUpperCase(); }
}

export function useAdminSecretOperations(): ContextValue {
  const value = useContext(Context);
  if (!value) throw new Error("useAdminSecretOperations must be used inside AdminSecretOperationProvider.");
  return value;
}
