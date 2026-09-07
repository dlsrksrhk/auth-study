"use client";

import {useState} from "react";

import {Alert, AlertDescription, AlertTitle} from "@/components/ui/alert";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import {Label} from "@/components/ui/label";
import type {Department} from "@/features/departments/department-api";
import {isApiProblemError} from "@/lib/api/problem";
import {userApi, type Membership, type MembershipRole, type User} from "./user-api";

type Props = {
  companyCode: string;
  user: User;
  memberships: Membership[];
  departments: Department[];
  positionActive: boolean;
  onRefresh(): void
};
const roleLabels: Record<MembershipRole, string> = {HEAD: "부서장", DEPUTY_HEAD: "부부서장", MEMBER: "구성원"};

export function MembershipEditor({companyCode, user, memberships, departments, positionActive, onRefresh}: Props) {
  const active = memberships.filter((item) => item.endedAt === null);
  const ended = memberships.filter((item) => item.endedAt !== null);
  const [pendingId, setPendingId] = useState<number | "activate" | "assign" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const [validation, setValidation] = useState<string[]>([]);
  const [confirm, setConfirm] = useState<{ kind: "activate" | "end"; membership?: Membership } | null>(null);
  const [departmentCode, setDepartmentCode] = useState("");
  const [newRole, setNewRole] = useState<MembershipRole>("MEMBER");
  const [newPrimary, setNewPrimary] = useState(false);

  const department = (id: number) => departments.find((item) => item.id === id);

  function showError(cause: unknown) {
    if (isApiProblemError(cause)) {
      const message = cause.status === 409 && /active head/i.test(cause.detail ?? "") ? "이 부서에는 이미 활성 부서장이 있습니다." : cause.status === 409 && cause.code === "OPTIMISTIC_LOCK_CONFLICT" ? "다른 관리자가 소속을 수정했습니다. 최신 정보를 다시 불러와 주세요." : cause.detail ?? cause.title;
      setError(message);
      setTraceId(cause.traceId);
      if (cause.status === 409 && cause.code === "OPTIMISTIC_LOCK_CONFLICT") onRefresh();
    } else setError(cause instanceof Error ? cause.message : "소속 요청을 처리하지 못했습니다.");
  }

  async function update(item: Membership, changes: Partial<Pick<Membership, "role" | "primary">>) {
    if (user.status === "ACTIVE" && item.primary && changes.primary === false) {
      setError("활성 사용자의 유일한 주 소속은 종료하거나 일반 소속으로 변경할 수 없습니다.");
      return;
    }
    setPendingId(item.id);
    setError(null);
    setTraceId(null);
    try {
      await userApi.updateMembership(companyCode, user.code, item.id, {
        role: changes.role ?? item.role,
        primary: changes.primary ?? item.primary,
        version: item.version
      });
      onRefresh();
    } catch (cause) {
      showError(cause);
    } finally {
      setPendingId(null);
    }
  }

  async function assign(event: React.FormEvent) {
    event.preventDefault();
    setPendingId("assign");
    setError(null);
    setTraceId(null);
    try {
      await userApi.assignMembership(companyCode, user.code, {
        departmentCode,
        role: newRole,
        primary: newPrimary,
        startedAt: new Date().toISOString()
      });
      setDepartmentCode("");
      setNewPrimary(false);
      onRefresh();
    } catch (cause) {
      showError(cause);
    } finally {
      setPendingId(null);
    }
  }

  function requestActivation() {
    const messages: string[] = [];
    if (active.filter((item) => item.primary).length !== 1) messages.push("주 소속을 하나 지정해 주세요.");
    if (!positionActive) messages.push("활성 직위를 지정해 주세요.");
    setValidation(messages);
    if (!messages.length) setConfirm({kind: "activate"});
  }

  function requestEnd(item: Membership) {
    if (user.status === "ACTIVE" && item.primary) {
      setError("활성 사용자의 유일한 주 소속은 종료하거나 일반 소속으로 변경할 수 없습니다.");
      return;
    }
    setConfirm({kind: "end", membership: item});
  }

  async function confirmed() {
    if (!confirm) return;
    const id = confirm.kind === "activate" ? "activate" : confirm.membership!.id;
    setPendingId(id);
    setError(null);
    setTraceId(null);
    try {
      if (confirm.kind === "activate") await userApi.changeStatus(companyCode, user.code, "ACTIVE", user.version);
      else await userApi.endMembership(companyCode, user.code, confirm.membership!.id, confirm.membership!.version);
      setConfirm(null);
      onRefresh();
    } catch (cause) {
      showError(cause);
    } finally {
      setPendingId(null);
    }
  }

  return <div className="space-y-5">
    {error ? <Alert aria-live="assertive"
                    variant="destructive"><AlertTitle>{error}</AlertTitle><AlertDescription>{traceId ? `추적 ID: ${traceId}` : null}</AlertDescription></Alert> : null}
    {validation.length ?
        <Alert aria-live="assertive" variant="destructive"><AlertTitle>활성화 조건을 확인해 주세요.</AlertTitle><AlertDescription>
          <ul className="list-disc pl-5">{validation.map((item) => <li key={item}>{item}</li>)}</ul>
        </AlertDescription></Alert> : null}
    {user.status !== "ACTIVE" && user.status !== "RESIGNED" ?
        <Button onClick={requestActivation}>사용자 활성화</Button> : null}
    <section aria-label="활성 소속"><h3 className="font-semibold">활성 소속</h3>
      <div className="mt-3 space-y-3">{active.map((item) => {
        const dept = department(item.departmentId);
        return <div className="grid gap-3 rounded-lg border p-3 sm:grid-cols-[1fr_11rem_auto_auto] sm:items-center"
                    key={item.id}>
          <div><span className="font-mono font-semibold">{dept?.code ?? item.departmentId}</span> {item.primary ?
              <Badge>주 소속</Badge> : null}<p className="text-xs text-slate-500">{dept?.name}</p></div>
          <div><Label htmlFor={`role-${item.id}`} className="sr-only">{dept?.code} 역할</Label><select
              aria-label={`${dept?.code} 역할`} id={`role-${item.id}`} disabled={pendingId !== null}
              className="h-9 w-full rounded-md border px-2 text-sm" value={item.role}
              onChange={(event) => void update(item, {role: event.target.value as MembershipRole})}>{Object.entries(roleLabels).map(([key, label]) =>
              <option key={key} value={key}>{label}</option>)}</select></div>
          {!item.primary ?
              <Button aria-label={`${dept?.code}를 주 소속으로 지정`} disabled={pendingId !== null} size="sm" variant="outline"
                      onClick={() => void update(item, {primary: true})}>주 소속 지정</Button> :
              <span className="text-center text-xs text-slate-500">현재 주 소속</span>}<Button
            aria-label={`${dept?.code} 소속 종료`} disabled={pendingId !== null} size="sm" variant="outline"
            onClick={() => requestEnd(item)}>종료</Button></div>;
      })}{active.length === 0 ? <p className="text-sm text-slate-500">활성 소속이 없습니다.</p> : null}</div>
    </section>
    <form className="grid gap-3 rounded-lg border border-dashed p-3 sm:grid-cols-[1fr_10rem_auto_auto] sm:items-end"
          onSubmit={assign}>
      <div><Label htmlFor="new-department">부서 추가</Label><select id="new-department"
                                                                className="mt-2 h-9 w-full rounded-md border px-2 text-sm"
                                                                required value={departmentCode}
                                                                onChange={(event) => setDepartmentCode(event.target.value)}>
        <option value="">선택</option>
        {departments.filter((item) => item.status === "ACTIVE" && !active.some((membership) => membership.departmentId === item.id)).map((item) =>
            <option key={item.id} value={item.code}>{item.code} · {item.name}</option>)}</select></div>
      <div><Label htmlFor="new-role">역할</Label><select id="new-role"
                                                       className="mt-2 h-9 w-full rounded-md border px-2 text-sm"
                                                       value={newRole}
                                                       onChange={(event) => setNewRole(event.target.value as MembershipRole)}>{Object.entries(roleLabels).map(([key, label]) =>
          <option key={key} value={key}>{label}</option>)}</select></div>
      <label className="flex h-9 items-center gap-2 text-sm"><input checked={newPrimary} type="checkbox"
                                                                    onChange={(event) => setNewPrimary(event.target.checked)}/>주
        소속</label><Button disabled={pendingId !== null} type="submit">소속 추가</Button></form>
    <section aria-label="종료된 소속 이력"><h3 className="font-semibold">종료된 소속 이력</h3>
      <div className="mt-3 overflow-x-auto">
        <table className="w-full text-left text-sm">
          <thead>
          <tr>
            <th className="p-2">부서</th>
            <th className="p-2">역할</th>
            <th className="p-2">상태</th>
            <th className="p-2">종료 시각</th>
          </tr>
          </thead>
          <tbody>{ended.map((item) => <tr className="border-t" key={item.id}>
            <td className="p-2">{department(item.departmentId)?.code ?? item.departmentId}</td>
            <td className="p-2">{roleLabels[item.role]}</td>
            <td className="p-2">종료</td>
            <td className="p-2">{item.endedAt}</td>
          </tr>)}</tbody>
        </table>
        {!ended.length ? <p className="p-3 text-sm text-slate-500">종료 이력이 없습니다.</p> : null}</div>
    </section>
    <Dialog open={Boolean(confirm)} onOpenChange={(next) => !pendingId && !next && setConfirm(null)}><DialogContent
        showCloseButton={pendingId === null}><DialogHeader><DialogTitle>{confirm?.kind === "activate" ? "사용자를 활성화할까요?" : "소속을 종료할까요?"}</DialogTitle><DialogDescription>{confirm?.kind === "activate" ? "활성 직위와 정확히 하나의 주 소속을 서버가 다시 검증합니다." : "종료된 소속은 이력으로 보존됩니다."}</DialogDescription></DialogHeader><DialogFooter><Button
        disabled={pendingId !== null} variant="outline" onClick={() => setConfirm(null)}>취소</Button><Button
        disabled={pendingId !== null}
        onClick={() => void confirmed()}>{pendingId !== null ? "처리 중" : "확인"}</Button></DialogFooter></DialogContent></Dialog>
  </div>;
}
