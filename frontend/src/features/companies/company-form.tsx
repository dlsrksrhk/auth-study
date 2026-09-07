"use client";

import {useRef, useState} from "react";

import {Alert, AlertDescription, AlertTitle} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import {Input} from "@/components/ui/input";
import {Label} from "@/components/ui/label";
import {companyApi, type Company, type CompanyStatus} from "./company-api";
import {isApiProblemError} from "@/lib/api/problem";

type Props = {
  company: Company | null;
  open: boolean;
  onOpenChange(open: boolean): void;
  onSaved(company: Company): void;
};

type Fields = "code" | "name" | "emailDomain" | "status";

export function CompanyForm({company, open, onOpenChange, onSaved}: Props) {
  const [code, setCode] = useState(company?.code ?? "");
  const [name, setName] = useState(company?.name ?? "");
  const [emailDomain, setEmailDomain] = useState(company?.emailDomain ?? "");
  const [status, setStatus] = useState<CompanyStatus>(company?.status ?? "ACTIVE");
  const [pending, setPending] = useState(false);
  const [confirmInactive, setConfirmInactive] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<Fields, string>>>({});
  const [globalError, setGlobalError] = useState<string | null>(null);
  const [traceId, setTraceId] = useState<string | null>(null);
  const codeRef = useRef<HTMLInputElement>(null);
  const nameRef = useRef<HTMLInputElement>(null);
  const emailDomainRef = useRef<HTMLInputElement>(null);
  const statusRef = useRef<HTMLSelectElement>(null);

  function focusField(field?: Fields) {
    const target = field === "code" ? codeRef.current
        : field === "emailDomain" ? emailDomainRef.current
            : field === "status" ? statusRef.current
                : nameRef.current;
    target?.focus();
  }

  async function save() {
    setPending(true);
    setErrors({});
    setGlobalError(null);
    setTraceId(null);
    try {
      const saved = company
          ? await companyApi.update(company.code, {name, status, version: company.version})
          : await companyApi.create({code, name, emailDomain});
      onSaved(saved);
      onOpenChange(false);
    } catch (cause) {
      if (isApiProblemError(cause)) {
        const fieldErrors = Object.fromEntries(cause.fieldErrors.map((item) => [item.field, item.message])) as Partial<Record<Fields, string>>;
        setErrors(fieldErrors);
        setGlobalError(cause.fieldErrors.length ? cause.detail ?? cause.title : cause.detail ?? cause.title);
        setTraceId(cause.traceId);
        const first = cause.fieldErrors[0]?.field as Fields | undefined;
        requestAnimationFrame(() => focusField(first));
      } else {
        setGlobalError("요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
      }
    } finally {
      setPending(false);
      setConfirmInactive(false);
    }
  }

  function requestSave(event: React.FormEvent) {
    event.preventDefault();
    if (company?.status === "ACTIVE" && status === "INACTIVE") {
      setConfirmInactive(true);
      return;
    }
    void save();
  }

  return (
      <>
        <Dialog open={open} onOpenChange={(next) => !pending && onOpenChange(next)}>
          <DialogContent className="sm:max-w-lg">
            <DialogHeader>
              <DialogTitle>{company ? "회사 수정" : "회사 생성"}</DialogTitle>
              <DialogDescription>회사 식별 정보와 운영 상태를 입력합니다.</DialogDescription>
            </DialogHeader>
            <form onSubmit={requestSave}>
              <div className="space-y-4 py-2">
                {globalError ? (
                    <Alert aria-live="assertive" variant="destructive">
                      <AlertTitle>저장하지 못했습니다</AlertTitle>
                      <AlertDescription>{globalError}{traceId ?
                          <span className="block">추적 ID: {traceId}</span> : null}</AlertDescription>
                    </Alert>
                ) : null}
                <Field label="코드" error={errors.code}>
                  <Input
                      aria-describedby={errors.code ? "company-code-error" : undefined}
                      disabled={Boolean(company)}
                      id="company-code"
                      maxLength={50}
                      onChange={(event) => setCode(event.target.value)}
                      ref={codeRef}
                      required
                      value={code}
                  />
                </Field>
                <Field label="회사명" error={errors.name}>
                  <Input id="company-name" maxLength={200} onChange={(event) => setName(event.target.value)}
                         ref={nameRef} required value={name}/>
                </Field>
                <Field label="이메일 도메인" error={errors.emailDomain}>
                  <Input disabled={Boolean(company)} id="company-email-domain" maxLength={253}
                         onChange={(event) => setEmailDomain(event.target.value)} ref={emailDomainRef} required
                         type="text" value={emailDomain}/>
                </Field>
                {company ? (
                    <div className="space-y-2">
                      <Label htmlFor="company-status">상태</Label>
                      <select className="h-9 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"
                              id="company-status" onChange={(event) => setStatus(event.target.value as CompanyStatus)}
                              ref={statusRef} value={status}>
                        <option value="ACTIVE">활성</option>
                        <option value="INACTIVE">비활성</option>
                      </select>
                    </div>
                ) : null}
              </div>
              <DialogFooter className="mt-4">
                <Button disabled={pending} type="button" variant="outline"
                        onClick={() => onOpenChange(false)}>취소</Button>
                <Button disabled={pending} type="submit">{pending ? "저장 중" : "저장"}</Button>
              </DialogFooter>
            </form>
          </DialogContent>
        </Dialog>
        <Dialog open={confirmInactive} onOpenChange={(next) => !pending && setConfirmInactive(next)}>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>회사를 비활성화할까요?</DialogTitle>
              <DialogDescription>소속 계정은 새 로그인과 토큰 갱신을 할 수 없습니다. 조직 데이터는 삭제되지 않습니다.</DialogDescription>
            </DialogHeader>
            <DialogFooter>
              <Button disabled={pending} onClick={() => setConfirmInactive(false)} variant="outline">취소</Button>
              <Button disabled={pending} onClick={() => void save()}
                      variant="destructive">{pending ? "처리 중" : "비활성화"}</Button>
            </DialogFooter>
          </DialogContent>
        </Dialog>
      </>
  );
}

function Field({label, error, children}: { label: string; error?: string; children: React.ReactNode }) {
  const id = label === "코드" ? "company-code" : label === "회사명" ? "company-name" : "company-email-domain";
  return (
      <div className="space-y-2">
        <Label htmlFor={id}>{label}</Label>
        {children}
        {error ? <p className="text-sm text-red-700" id={`${id}-error`}>{error}</p> : null}
      </div>
  );
}
