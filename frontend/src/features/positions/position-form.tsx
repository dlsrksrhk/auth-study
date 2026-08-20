"use client";

import { useRef, useState } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { isApiProblemError } from "@/lib/api/problem";
import { positionApi, type Position } from "./position-api";

type Props = {
  companyCode: string;
  position: Position | null;
  open: boolean;
  onOpenChange(open: boolean): void;
  onSaved(position: Position): void;
  onReload(): void;
};

type Field = "code" | "name" | "level" | "displayOrder" | "active";

export function PositionForm({ companyCode, position, open, onOpenChange, onSaved, onReload }: Props) {
  const [code, setCode] = useState(position?.code ?? "");
  const [name, setName] = useState(position?.name ?? "");
  const [level, setLevel] = useState(position?.level ?? 10);
  const [displayOrder, setDisplayOrder] = useState(position?.displayOrder ?? 10);
  const [active, setActive] = useState(position?.active ?? true);
  const [pending, setPending] = useState(false);
  const [confirmInactive, setConfirmInactive] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [globalError, setGlobalError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const codeRef = useRef<HTMLInputElement>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  const levelRef = useRef<HTMLInputElement>(null);
  const displayOrderRef = useRef<HTMLInputElement>(null);
  const activeRef = useRef<HTMLInputElement>(null);

  function focusField(field?: Field) {
    const target = field === "code" ? codeRef.current
      : field === "level" ? levelRef.current
        : field === "displayOrder" ? displayOrderRef.current
          : field === "active" ? activeRef.current
            : nameRef.current;
    target?.focus();
  }

  async function save() {
    setPending(true);
    setErrors({});
    setGlobalError(null);
    setTraceId(null);
    try {
      const saved = position
        ? await positionApi.update(companyCode, position.code, { name, level, displayOrder, active, version: position.version })
        : await positionApi.create(companyCode, { code, name, level, displayOrder });
      onSaved(saved);
      onOpenChange(false);
    } catch (cause) {
      if (isApiProblemError(cause)) {
        const conflict = cause.status === 409 && cause.code === "OPTIMISTIC_LOCK_CONFLICT";
        setStale(conflict);
        setGlobalError(conflict ? "다른 사용자가 수정했습니다." : cause.detail ?? cause.title);
        setTraceId(cause.traceId);
        const fieldErrors = Object.fromEntries(cause.fieldErrors.map((item) => [item.field, item.message])) as Partial<Record<Field, string>>;
        setErrors(fieldErrors);
        const first = cause.fieldErrors[0]?.field as Field | undefined;
        requestAnimationFrame(() => focusField(first));
      } else {
        setGlobalError("요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setPending(false);
      setConfirmInactive(false);
    }
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (position?.active && !active) setConfirmInactive(true);
    else void save();
  }

  return (
    <>
      <Dialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader><DialogTitle>{position ? "직위 수정" : "직위 생성"}</DialogTitle><DialogDescription>직위 코드와 표시 순서를 관리합니다.</DialogDescription></DialogHeader>
          <form onSubmit={submit}>
            <div className="space-y-4 py-2">
              {globalError ? <Alert aria-live="assertive" variant="destructive"><AlertTitle>{globalError}</AlertTitle><AlertDescription>{traceId ? `추적 ID: ${traceId}` : null}{stale ? <Button className="mt-3 block" onClick={() => { onOpenChange(false); onReload(); }} type="button" variant="outline">최신 정보 다시 불러오기</Button> : null}</AlertDescription></Alert> : null}
              <Field error={errors.code} id="position-code" label="코드"><Input id="position-code" maxLength={50} onChange={(event) => setCode(event.target.value)} readOnly={Boolean(position)} ref={codeRef} required value={code} /></Field>
              <Field error={errors.name} id="position-name" label="직위명"><Input id="position-name" maxLength={100} onChange={(event) => setName(event.target.value)} ref={nameRef} required value={name} /></Field>
              <div className="grid grid-cols-2 gap-4">
                <Field error={errors.level} id="position-level" label="레벨"><Input id="position-level" onChange={(event) => setLevel(event.target.valueAsNumber)} ref={levelRef} required type="number" value={level} /></Field>
                <Field error={errors.displayOrder} id="position-display-order" label="표시 순서"><Input id="position-display-order" onChange={(event) => setDisplayOrder(event.target.valueAsNumber)} ref={displayOrderRef} required type="number" value={displayOrder} /></Field>
              </div>
              {position ? <label className="flex items-center gap-2 text-sm font-medium"><input checked={active} className="size-4 accent-teal-700" onChange={(event) => setActive(event.target.checked)} ref={activeRef} type="checkbox" />활성</label> : null}
            </div>
            <DialogFooter className="mt-4"><Button disabled={pending} onClick={() => onOpenChange(false)} type="button" variant="outline">취소</Button><Button disabled={pending || stale} type="submit">{pending ? "저장 중" : "저장"}</Button></DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog open={confirmInactive} onOpenChange={(next) => !pending && setConfirmInactive(next)}>
        <DialogContent>
          <DialogHeader><DialogTitle>직위를 비활성화할까요?</DialogTitle><DialogDescription>새 사용자에게 이 직위를 지정할 수 없게 됩니다. 기존 사용자 데이터는 유지됩니다.</DialogDescription></DialogHeader>
          <DialogFooter><Button disabled={pending} onClick={() => setConfirmInactive(false)} variant="outline">취소</Button><Button disabled={pending} onClick={() => void save()} variant="destructive">{pending ? "처리 중" : "비활성화"}</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

function Field({ id, label, error, children }: { id: string; label: string; error?: string; children: React.ReactNode }) {
  return <div className="space-y-2"><Label htmlFor={id}>{label}</Label>{children}{error ? <p className="text-sm text-red-700">{error}</p> : null}</div>;
}
