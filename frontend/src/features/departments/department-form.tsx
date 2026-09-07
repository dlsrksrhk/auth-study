"use client";

import {useEffect, useRef, useState} from "react";

import {Alert, AlertDescription, AlertTitle} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle
} from "@/components/ui/dialog";
import {Input} from "@/components/ui/input";
import {Label} from "@/components/ui/label";
import {isApiProblemError} from "@/lib/api/problem";
import {departmentApi, type Department, type DepartmentStatus} from "./department-api";

type Mode = "create" | "edit" | "move";
type Props = {
  companyCode: string;
  departments: Department[];
  department?: Department;
  mode: Mode;
  open: boolean;
  onOpenChange(open: boolean): void;
  onSaved(): void;
  onConflict?(traceId: string): void
};

function descendantsOf(departments: Department[], id: number): Set<number> {
  const result = new Set<number>([id]);
  let changed = true;
  while (changed) {
    changed = false;
    for (const item of departments) if (item.parentDepartmentId !== null && result.has(item.parentDepartmentId) && !result.has(item.id)) {
      result.add(item.id);
      changed = true;
    }
  }
  return result;
}

export function DepartmentForm({
                                 companyCode,
                                 departments,
                                 department,
                                 mode,
                                 open,
                                 onOpenChange,
                                 onSaved,
                                 onConflict
                               }: Props) {
  const [code, setCode] = useState(department?.code ?? "");
  const [name, setName] = useState(department?.name ?? "");
  const [parentCode, setParentCode] = useState(department ? departments.find((item) => item.id === department.parentDepartmentId)?.code ?? "" : "");
  const [status, setStatus] = useState<DepartmentStatus>(department?.status ?? "ACTIVE");
  const [pending, setPending] = useState(false);
  const [confirmInactive, setConfirmInactive] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const codeRef = useRef<HTMLInputElement>(null);
  const invalidParents = department ? descendantsOf(departments, department.id) : new Set<number>();

  useEffect(() => {
    if (!open) return;
    requestAnimationFrame(() => codeRef.current?.focus());
  }, [open]);

  async function save() {
    if (mode === "move" && department) {
      const parent = departments.find((item) => item.code === parentCode);
      if (parent && invalidParents.has(parent.id)) {
        setError("자기 자신이나 하위 부서를 상위 부서로 선택할 수 없습니다.");
        return;
      }
    }
    setPending(true);
    setError(null);
    setTraceId(null);
    try {
      if (department) await departmentApi.update(companyCode, department.code, {
        name,
        parentCode: parentCode || null,
        status,
        version: department.version
      });
      else await departmentApi.create(companyCode, {code, name, parentCode: parentCode || null});
      onOpenChange(false);
      onSaved();
    } catch (cause) {
      if (isApiProblemError(cause)) {
        const conflict = cause.status === 409 && cause.code === "OPTIMISTIC_LOCK_CONFLICT";
        if (conflict && onConflict) {
          onOpenChange(false);
          onConflict(cause.traceId);
          return;
        }
        setError(conflict ? "다른 관리자가 수정했습니다. 최신 정보를 다시 불러와 주세요." : cause.detail ?? cause.title);
        setTraceId(cause.traceId);
      } else setError(cause instanceof Error ? cause.message : "부서 요청을 처리하지 못했습니다.");
    } finally {
      setPending(false);
      setConfirmInactive(false);
    }
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (mode === "edit" && department?.status === "ACTIVE" && status === "INACTIVE") setConfirmInactive(true);
    else void save();
  }

  const title = mode === "create" ? "부서 생성" : mode === "move" ? `${department?.code} 이동` : `${department?.code} 수정`;
  return <>
    <Dialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
      <DialogContent className="sm:max-w-lg" showCloseButton={!pending}>
        <DialogHeader><DialogTitle>{title}</DialogTitle><DialogDescription>부서 정보와 조직상 위치를 서버 기준으로
          저장합니다.</DialogDescription></DialogHeader>
        <form aria-describedby={error ? "department-form-error" : undefined} onSubmit={submit}>
          {error ? <Alert id="department-form-error" aria-live="assertive"
                          variant="destructive"><AlertTitle>{error}</AlertTitle><AlertDescription>{traceId ? `추적 ID: ${traceId}` : null}</AlertDescription></Alert> : null}
          <div className="space-y-4 py-3">
            {mode !== "move" ?
                <div className="space-y-2"><Label htmlFor="department-code">부서 코드</Label><Input id="department-code"
                                                                                                ref={codeRef}
                                                                                                readOnly={Boolean(department)}
                                                                                                required maxLength={50}
                                                                                                value={code}
                                                                                                onChange={(event) => setCode(event.target.value)}/>
                </div> : null}
            {mode !== "move" ?
                <div className="space-y-2"><Label htmlFor="department-name">부서명</Label><Input id="department-name"
                                                                                              required maxLength={100}
                                                                                              value={name}
                                                                                              onChange={(event) => setName(event.target.value)}/>
                </div> : null}
            <div className="space-y-2"><Label
                htmlFor="department-parent">{mode === "move" ? "새 상위 부서" : "상위 부서"}</Label><select
                id="department-parent" className="h-9 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"
                value={parentCode} onChange={(event) => setParentCode(event.target.value)}>
              <option value="">최상위 부서</option>
              {departments.map((item) => <option disabled={invalidParents.has(item.id)} key={item.id}
                                                 value={item.code}>{item.name} ({item.code})</option>)}</select></div>
            {mode === "edit" ?
                <div className="space-y-2"><Label htmlFor="department-status">상태</Label><select id="department-status"
                                                                                                className="h-9 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"
                                                                                                value={status}
                                                                                                onChange={(event) => setStatus(event.target.value as DepartmentStatus)}>
                  <option value="ACTIVE">활성</option>
                  <option value="INACTIVE">비활성</option>
                </select></div> : null}
          </div>
          <DialogFooter><Button disabled={pending} type="button" variant="outline"
                                onClick={() => onOpenChange(false)}>취소</Button><Button disabled={pending}
                                                                                       type="submit">{pending ? "저장 중" : mode === "move" ? "이동 저장" : "저장"}</Button></DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
    <Dialog open={confirmInactive} onOpenChange={(next) => !pending && setConfirmInactive(next)}><DialogContent
        showCloseButton={!pending}><DialogHeader><DialogTitle>부서를 비활성화할까요?</DialogTitle><DialogDescription>활성 하위 부서나 활성
      사용자 소속이 있으면 서버가 409로 거부합니다.</DialogDescription></DialogHeader><DialogFooter><Button disabled={pending}
                                                                                          variant="outline"
                                                                                          onClick={() => setConfirmInactive(false)}>취소</Button><Button
        disabled={pending} variant="destructive"
        onClick={() => void save()}>{pending ? "처리 중" : "비활성화"}</Button></DialogFooter></DialogContent></Dialog>
  </>;
}
