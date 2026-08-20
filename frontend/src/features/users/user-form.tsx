"use client";

import { useEffect, useRef, useState } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import type { Position } from "@/features/positions/position-api";
import { isApiProblemError } from "@/lib/api/problem";
import { userApi, type User } from "./user-api";
import { useAdminSecretOperations } from "./admin-secret-operation-provider";

type Props = { companyCode: string; user?: User; positions: Position[]; open: boolean; onOpenChange(open: boolean): void; onRefresh(): void; onConflict?(traceId: string): void };
type Draft = { code: string; employeeNumber: string; name: string; loginEmail: string; phone: string; hiredAt: string; workplace: string; profileImageUrl: string; positionCode: string };
const blank: Draft = { code: "", employeeNumber: "", name: "", loginEmail: "", phone: "", hiredAt: "", workplace: "", profileImageUrl: "", positionCode: "" };

export function UserForm({ companyCode, user, positions, open, onOpenChange, onRefresh, onConflict }: Props) {
  const currentPosition = positions.find((position) => position.id === user?.positionId);
  const initial = user ? { code: user.code, employeeNumber: user.employeeNumber, name: user.name, loginEmail: user.loginEmail, phone: user.phone, hiredAt: user.hiredAt, workplace: user.workplace, profileImageUrl: user.profileImageUrl ?? "", positionCode: currentPosition?.code ?? "" } : blank;
  const [draft, setDraft] = useState<Draft>(initial);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const firstRef = useRef<HTMLInputElement>(null);
  const mutation = useRef<AbortController | null>(null);
  const mounted = useRef(true);
  const lifecycleEpoch = useRef(0);
  const secretOperations = useAdminSecretOperations();

  useEffect(() => {
    if (!open) return;
    requestAnimationFrame(() => firstRef.current?.focus());
  }, [open]);
  useEffect(() => {
    mounted.current = true;
    const epoch = ++lifecycleEpoch.current;
    return () => {
      if (lifecycleEpoch.current === epoch) lifecycleEpoch.current += 1;
      mounted.current = false;
      mutation.current?.abort();
    };
  }, []);

  function field<K extends keyof Draft>(key: K, value: Draft[K]) { setDraft((old) => ({ ...old, [key]: value })); }
  async function submit(event: React.FormEvent) {
    event.preventDefault(); setError(null); setTraceId(null);
    const lifecycle = lifecycleEpoch.current;
    const isCurrentLifecycle = () => mounted.current && lifecycleEpoch.current === lifecycle;
    try {
      if (user) {
        mutation.current?.abort(); const controller = new AbortController(); mutation.current = controller; setPending(true);
        await userApi.update(companyCode, user.code, { name: draft.name, phone: draft.phone, hiredAt: draft.hiredAt, workplace: draft.workplace, profileImageUrl: draft.profileImageUrl, positionCode: draft.positionCode, version: user.version }, controller.signal);
        if (controller.signal.aborted || !isCurrentLifecycle()) return;
        onOpenChange(false); onRefresh();
        if (mutation.current === controller && !controller.signal.aborted) setPending(false);
      } else {
        const completed = await secretOperations.createUser(companyCode, draft);
        if (!isCurrentLifecycle() || !completed) return;
        setDraft(blank);
        onOpenChange(false);
        onRefresh();
      }
    } catch (cause) {
      if (!isCurrentLifecycle()) return;
      const controller = mutation.current;
      if (controller?.signal.aborted) return;
      if (isApiProblemError(cause)) {
        const conflict = cause.status === 409 && cause.code === "OPTIMISTIC_LOCK_CONFLICT";
        if (conflict && onConflict) { onOpenChange(false); onConflict(cause.traceId); return; }
        setError(conflict ? "다른 관리자가 수정했습니다. 최신 정보를 다시 불러와 주세요." : cause.detail ?? cause.title); setTraceId(cause.traceId);
      }
      else setError(cause instanceof Error ? cause.message : "사용자 요청을 처리하지 못했습니다.");
    } finally {
      const controller = mutation.current;
      if (controller && !controller.signal.aborted) setPending(false);
    }
  }
  const selectablePositions = positions.filter((position) => position.active || position.id === user?.positionId);
  const operationPending = pending || (!user && Boolean(secretOperations.pending));

  return <Dialog open={open} onOpenChange={(next) => !operationPending && onOpenChange(next)}><DialogContent className="sm:max-w-xl" showCloseButton={!operationPending}><DialogHeader><DialogTitle>{user ? "사용자 프로필 수정" : "사용자 생성"}</DialogTitle><DialogDescription>로그인 계정과 HR 프로필에 사용할 정보를 입력합니다.</DialogDescription></DialogHeader><form aria-describedby={error ? "user-form-error" : undefined} onSubmit={submit}>
      {error ? <Alert id="user-form-error" aria-live="assertive" variant="destructive"><AlertTitle>{error}</AlertTitle><AlertDescription>{traceId ? `추적 ID: ${traceId}` : null}</AlertDescription></Alert> : null}
      <div className="grid max-h-[65vh] gap-4 overflow-y-auto py-3 sm:grid-cols-2">
        <Field id="user-code" label="사용자 코드"><Input id="user-code" ref={firstRef} readOnly={Boolean(user)} required maxLength={50} value={draft.code} onChange={(event) => field("code", event.target.value)} /></Field>
        <Field id="employee-number" label="사번"><Input id="employee-number" readOnly={Boolean(user)} required maxLength={50} value={draft.employeeNumber} onChange={(event) => field("employeeNumber", event.target.value)} /></Field>
        <Field id="user-name" label="이름"><Input id="user-name" required maxLength={100} value={draft.name} onChange={(event) => field("name", event.target.value)} /></Field>
        <Field id="login-email" label="로그인 이메일"><Input id="login-email" readOnly={Boolean(user)} required type="email" maxLength={254} value={draft.loginEmail} onChange={(event) => field("loginEmail", event.target.value)} /></Field>
        <Field id="phone" label="전화번호"><Input id="phone" required maxLength={50} value={draft.phone} onChange={(event) => field("phone", event.target.value)} /></Field>
        <Field id="hired-at" label="입사일"><Input id="hired-at" required type="date" value={draft.hiredAt} onChange={(event) => field("hiredAt", event.target.value)} /></Field>
        <Field id="workplace" label="근무지"><Input id="workplace" required maxLength={100} value={draft.workplace} onChange={(event) => field("workplace", event.target.value)} /></Field>
        <Field id="position" label="직위"><select id="position" className="h-9 w-full rounded-md border border-slate-300 bg-white px-3 text-sm" required value={draft.positionCode} onChange={(event) => field("positionCode", event.target.value)}><option value="">선택</option>{selectablePositions.map((position) => <option key={position.id} value={position.code}>{position.name} ({position.code})</option>)}</select></Field>
        <div className="space-y-2 sm:col-span-2"><Label htmlFor="profile-image-url">프로필 이미지 URL</Label><Input id="profile-image-url" maxLength={2048} value={draft.profileImageUrl} onChange={(event) => field("profileImageUrl", event.target.value)} /></div>
      </div><DialogFooter><Button disabled={operationPending} type="button" variant="outline" onClick={() => onOpenChange(false)}>취소</Button><Button disabled={operationPending} type="submit">{operationPending ? "저장 중" : user ? "프로필 저장" : "사용자 생성"}</Button></DialogFooter>
    </form></DialogContent></Dialog>;
}

function Field({ id, label, children }: { id: string; label: string; children: React.ReactNode }) { return <div className="space-y-2"><Label htmlFor={id}>{label}</Label>{children}</div>; }
