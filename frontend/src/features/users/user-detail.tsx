"use client";

import { useEffect, useRef, useState } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import type { Role } from "@/features/auth/auth-api";
import { departmentApi, type Department } from "@/features/departments/department-api";
import { positionApi, type Position } from "@/features/positions/position-api";
import { isApiProblemError } from "@/lib/api/problem";
import { resourceCode } from "@/lib/resource-code";
import { MembershipEditor } from "./membership-editor";
import { userApi, type Membership, type User, type UserStatus } from "./user-api";
import { UserForm } from "./user-form";
import { useAdminSecretOperations } from "./admin-secret-operation-provider";

type Props = { companyCode: string; userCode: string; actorRoles: Role[] };

export function UserDetail({ companyCode, userCode, actorRoles }: Props) {
  const [user, setUser] = useState<User | null>(null);
  const [positions, setPositions] = useState<Position[]>([]);
  const [departments, setDepartments] = useState<Department[]>([]);
  const [memberships, setMemberships] = useState<Membership[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [editing, setEditing] = useState(false);
  const [pending, setPending] = useState(false);
  const [confirmStatus, setConfirmStatus] = useState<UserStatus | null>(null);
  const [confirmAccountAction, setConfirmAccountAction] = useState<"reset" | "grant" | "revoke" | null>(null);
  const [notice, setNotice] = useState("");
  const requestId = useRef(0);
  const mounted = useRef(true);
  const lifecycleEpoch = useRef(0);
  const secretOperations = useAdminSecretOperations();
  let canonicalCompany = "", canonicalUser = "";
  try { canonicalCompany = decodeURIComponent(resourceCode(companyCode, "회사 코드")); canonicalUser = decodeURIComponent(resourceCode(userCode, "사용자 코드")); } catch { /* rendered below */ }
  useEffect(() => {
    mounted.current = true;
    const epoch = ++lifecycleEpoch.current;
    return () => {
      if (lifecycleEpoch.current === epoch) lifecycleEpoch.current += 1;
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (!canonicalCompany || !canonicalUser) return;
    const id = ++requestId.current; const controller = new AbortController();
    queueMicrotask(() => { if (id === requestId.current) { setLoading(true); setError(null); setTraceId(null); } });
    Promise.all([
      userApi.find(canonicalCompany, canonicalUser, controller.signal),
      positionApi.listAll(canonicalCompany, undefined, controller.signal),
      departmentApi.listAll(canonicalCompany, controller.signal),
      userApi.membershipsAll(canonicalCompany, canonicalUser, controller.signal),
    ]).then(([nextUser, nextPositions, nextDepartments, nextMemberships]) => {
      if (id !== requestId.current) return;
      setUser(nextUser); setPositions(nextPositions); setDepartments(nextDepartments); setMemberships(nextMemberships);
    }).catch((cause) => {
      if (id !== requestId.current || (cause instanceof DOMException && cause.name === "AbortError")) return;
      if (isApiProblemError(cause)) { setError(cause.detail ?? cause.title); setTraceId(cause.traceId); } else setError(cause instanceof Error ? cause.message : "사용자 상세를 불러오지 못했습니다.");
    }).finally(() => { if (id === requestId.current) setLoading(false); });
    return () => { controller.abort(); if (requestId.current === id) requestId.current += 1; };
  }, [canonicalCompany, canonicalUser, reload]);

  function showMutationError(cause: unknown) {
    if (isApiProblemError(cause)) { setError(cause.status === 409 && cause.code === "OPTIMISTIC_LOCK_CONFLICT" ? "다른 관리자가 수정했습니다. 최신 정보를 다시 불러왔습니다." : cause.detail ?? cause.title); setTraceId(cause.traceId); if (cause.status === 409) setReload((value) => value + 1); }
    else setError(cause instanceof Error ? cause.message : "요청을 처리하지 못했습니다.");
  }
  async function roleAction(action: "grant" | "revoke") {
    setPending(true); setError(null); setTraceId(null);
    try { if (action === "grant") await userApi.grantAdmin(canonicalCompany, canonicalUser); else await userApi.revokeAdmin(canonicalCompany, canonicalUser); setConfirmAccountAction(null); setNotice(action === "grant" ? "회사 관리자 권한을 지정했습니다." : "회사 관리자 권한을 회수했습니다."); setReload((value) => value + 1); }
    catch (cause) { showMutationError(cause); } finally { setPending(false); }
  }
  async function resetPassword() {
    const lifecycle = lifecycleEpoch.current;
    const completed = await secretOperations.resetPassword(canonicalCompany, canonicalUser, user?.loginEmail);
    if (mounted.current && lifecycleEpoch.current === lifecycle && completed) {
      setConfirmAccountAction(null);
      setNotice("임시 비밀번호를 재발급했습니다.");
      setReload((value) => value + 1);
    }
  }
  async function changeStatus() {
    if (!user || !confirmStatus) return; setPending(true); setError(null); setTraceId(null);
    try { await userApi.changeStatus(canonicalCompany, canonicalUser, confirmStatus, user.version); setConfirmStatus(null); setReload((value) => value + 1); }
    catch (cause) { showMutationError(cause); } finally { setPending(false); }
  }

  if (!canonicalCompany || !canonicalUser) return <section><h1 className="text-3xl font-semibold">사용자 상세</h1><p aria-live="assertive" className="mt-6 rounded-xl border bg-white p-8 text-red-700">회사 또는 사용자 코드가 올바르지 않습니다.</p></section>;
  if (loading) return <div aria-busy="true"><span className="sr-only">사용자 상세를 불러오는 중입니다.</span><Skeleton className="h-96 w-full" /></div>;
  if (!user) return <div className="rounded-xl border bg-white p-8"><p aria-live="assertive" className="text-red-700">{error ?? "사용자를 찾을 수 없습니다."} {traceId ? `(추적 ID: ${traceId})` : ""}</p><Button className="mt-3" variant="outline" onClick={() => setReload((value) => value + 1)}>다시 시도</Button></div>;
  const position = positions.find((item) => item.id === user.positionId);
  const systemAdmin = actorRoles.includes("SYSTEM_ADMIN");
  const targetCompanyAdmin = user.roles.includes("COMPANY_ADMIN");
  const mayResetPassword = systemAdmin || !targetCompanyAdmin;
  const secretOperationPending = Boolean(secretOperations.pending);
  const accountPending = pending || secretOperationPending;
  return <section aria-labelledby="user-detail-title" className="mx-auto max-w-7xl">
    <div className="flex flex-wrap items-end justify-between gap-4"><div><p className="font-mono text-xs font-semibold text-teal-700">{canonicalCompany} / {canonicalUser}</p><h1 className="mt-2 text-3xl font-semibold" id="user-detail-title">{user.name}</h1><p className="mt-2 text-sm text-slate-600">프로필, 계정, 상태와 복수 소속을 관리합니다.</p></div><div className="flex flex-wrap gap-2"><Button variant="outline" onClick={() => setEditing(true)}>프로필 수정</Button>{mayResetPassword ? <Button disabled={pending || secretOperationPending} variant="outline" onClick={() => setConfirmAccountAction("reset")}>임시 비밀번호 재발급</Button> : null}{systemAdmin ? targetCompanyAdmin ? <Button disabled={pending} variant="outline" onClick={() => setConfirmAccountAction("revoke")}>회사 관리자 회수</Button> : <Button disabled={pending} onClick={() => setConfirmAccountAction("grant")}>회사 관리자 지정</Button> : null}</div></div>
    {notice ? <p aria-live="polite" className="mt-4 text-sm text-teal-700">{notice}</p> : null}
    {error ? <Alert className="mt-5" aria-live="assertive" variant="destructive"><AlertTitle>{error}</AlertTitle><AlertDescription>{traceId ? `추적 ID: ${traceId}` : null}</AlertDescription></Alert> : null}
    <div className="mt-6 grid gap-4 lg:grid-cols-2">
      <Card title="프로필"><Details values={[["이름", user.name], ["사번", user.employeeNumber], ["전화번호", user.phone], ["입사일", user.hiredAt], ["근무지", user.workplace], ["프로필 이미지 URL", user.profileImageUrl || "없음"]]} /></Card>
      <Card title="상태"><div className="flex items-center gap-3"><Badge>{user.status}</Badge>{user.status === "ACTIVE" ? <><Button size="sm" variant="outline" onClick={() => setConfirmStatus("LOCKED")}>계정 잠금</Button><Button size="sm" variant="destructive" onClick={() => setConfirmStatus("RESIGNED")}>퇴사 처리</Button></> : null}</div></Card>
      <Card title="직위"><p>{position ? `${position.name} (${position.code})` : `ID ${user.positionId}`}</p><Badge variant={position?.active ? "default" : "secondary"}>{position?.active ? "활성" : "비활성"}</Badge></Card>
      <Card title="계정"><Details values={[["로그인 이메일", user.loginEmail], ["사용자 코드", user.code]]} /></Card>
      <div className="rounded-xl border bg-white p-5 shadow-sm lg:col-span-2"><h2 className="mb-4 text-lg font-semibold">부서 소속</h2><MembershipEditor companyCode={canonicalCompany} departments={departments} memberships={memberships} onRefresh={() => setReload((value) => value + 1)} positionActive={Boolean(position?.active)} user={user} /></div>
    </div>
    {editing ? <UserForm companyCode={canonicalCompany} onConflict={(latestTraceId) => { setNotice("다른 관리자가 수정했습니다. 최신 사용자 정보를 다시 불러왔습니다."); setTraceId(latestTraceId); setReload((value) => value + 1); }} onOpenChange={setEditing} onRefresh={() => setReload((value) => value + 1)} open positions={positions} user={user} /> : null}
    <Dialog open={Boolean(confirmStatus)} onOpenChange={(next) => !pending && !next && setConfirmStatus(null)}><DialogContent showCloseButton={!pending}><DialogHeader><DialogTitle>사용자 상태를 {confirmStatus === "LOCKED" ? "잠금" : "퇴사"}으로 변경할까요?</DialogTitle><DialogDescription>로그인과 활성 Refresh Token에 즉시 영향을 줍니다. 서버가 현재 버전을 다시 확인합니다.</DialogDescription></DialogHeader><DialogFooter><Button disabled={pending} variant="outline" onClick={() => setConfirmStatus(null)}>취소</Button><Button disabled={pending} variant="destructive" onClick={() => void changeStatus()}>{pending ? "처리 중" : "상태 변경"}</Button></DialogFooter></DialogContent></Dialog>
    <Dialog open={Boolean(confirmAccountAction)} onOpenChange={(next) => !accountPending && !next && setConfirmAccountAction(null)}><DialogContent showCloseButton={!accountPending}><DialogHeader><DialogTitle>{confirmAccountAction === "reset" ? "임시 비밀번호를 재발급할까요?" : `회사 관리자 권한을 ${confirmAccountAction === "grant" ? "지정" : "회수"}할까요?`}</DialogTitle><DialogDescription>완료하면 해당 계정의 현재 로그인 세션의 Refresh Token이 폐기됩니다.</DialogDescription></DialogHeader><DialogFooter><Button disabled={accountPending} variant="outline" onClick={() => setConfirmAccountAction(null)}>취소</Button><Button disabled={accountPending} onClick={() => confirmAccountAction === "reset" ? void resetPassword() : void roleAction(confirmAccountAction!)}>{accountPending ? "처리 중" : confirmAccountAction === "reset" ? "재발급 확인" : "권한 변경 확인"}</Button></DialogFooter></DialogContent></Dialog>
  </section>;
}

function Card({ title, children }: { title: string; children: React.ReactNode }) { return <section className="rounded-xl border bg-white p-5 shadow-sm"><h2 className="mb-3 text-lg font-semibold">{title}</h2>{children}</section>; }
function Details({ values }: { values: [string, string][] }) { return <dl className="grid gap-2">{values.map(([label, value]) => <div className="grid grid-cols-[8rem_1fr]" key={label}><dt className="text-sm text-slate-500">{label}</dt><dd className="break-all text-sm">{value}</dd></div>)}</dl>; }
