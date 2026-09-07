"use client";

import {useEffect, useRef, useState, type FormEvent} from "react";
import {useRouter} from "next/navigation";

import {AuthShell} from "@/components/auth/auth-shell";
import {Alert, AlertDescription, AlertTitle} from "@/components/ui/alert";
import {Button} from "@/components/ui/button";
import {Card, CardContent} from "@/components/ui/card";
import {Input} from "@/components/ui/input";
import {Label} from "@/components/ui/label";
import {useAuth} from "@/features/auth/auth-provider";
import {isApiProblemError} from "@/lib/api/problem";
import {validateNewPassword} from "./password-validation";

export default function ChangePasswordPage() {
  const auth = useAuth();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [errors, setErrors] = useState<string[]>([]);
  const [serverError, setServerError] = useState<{ message: string; traceId?: string } | null>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (errors.length > 0 || serverError) errorRef.current?.focus();
  }, [errors, serverError]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const currentPassword = String(data.get("currentPassword"));
    const newPassword = String(data.get("newPassword"));
    const confirmation = String(data.get("confirmation"));
    const clientErrors = validateNewPassword(newPassword);
    if (newPassword !== confirmation) clientErrors.push("새 비밀번호와 확인 값이 일치하지 않습니다.");
    setErrors(clientErrors);
    setServerError(null);
    if (clientErrors.length > 0) {
      return;
    }

    setSubmitting(true);
    try {
      await auth.changePassword(currentPassword, newPassword);
      router.replace("/login");
    } catch (error) {
      setServerError(
          isApiProblemError(error)
              ? {message: error.detail ?? error.title, traceId: error.traceId}
              : {message: "비밀번호를 변경할 수 없습니다. 잠시 후 다시 시도해 주세요."},
      );
    } finally {
      setSubmitting(false);
    }
  }

  return (
      <AuthShell
          eyebrow="필수 작업"
          title="새 비밀번호를 설정해 주세요"
          description="다른 기능을 사용하기 전에 임시 비밀번호를 변경해야 합니다."
      >
        <Card className="border-slate-200 bg-white shadow-lg shadow-slate-200/60">
          <CardContent>
            <form className="space-y-5" onSubmit={submit} noValidate>
              {errors.length > 0 || serverError ? (
                  <Alert ref={errorRef} role="alert" tabIndex={-1} variant="destructive">
                    <AlertTitle>입력 내용을 확인해 주세요</AlertTitle>
                    <AlertDescription>
                      {serverError ? <p>{serverError.message}</p> : null}
                      {serverError?.traceId ? <p className="text-xs">추적 ID: {serverError.traceId}</p> : null}
                      {errors.length > 0 ? (
                          <ul className="list-disc pl-4">
                            {errors.map((error) => <li key={error}>{error}</li>)}
                          </ul>
                      ) : null}
                    </AlertDescription>
                  </Alert>
              ) : null}
              <PasswordField id="currentPassword" label="현재 비밀번호" autoComplete="current-password"/>
              <PasswordField id="newPassword" label="새 비밀번호" autoComplete="new-password"/>
              <PasswordField id="confirmation" label="새 비밀번호 확인" autoComplete="new-password"/>
              <p className="text-xs leading-5 text-slate-500">
                12~64자, UTF-8 72바이트 이하이며 영문 대문자·소문자·숫자·특수문자를 각각 포함해야 합니다.
              </p>
              <Button className="w-full" type="submit" disabled={submitting} aria-busy={submitting}>
                {submitting ? "변경 중…" : "비밀번호 변경"}
              </Button>
            </form>
          </CardContent>
        </Card>
      </AuthShell>
  );
}

function PasswordField({id, label, autoComplete}: { id: string; label: string; autoComplete: string }) {
  return (
      <div className="space-y-2">
        <Label htmlFor={id}>{label}</Label>
        <Input id={id} name={id} type="password" required autoComplete={autoComplete}/>
      </div>
  );
}
