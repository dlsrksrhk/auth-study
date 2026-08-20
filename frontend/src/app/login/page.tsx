"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { useRouter } from "next/navigation";

import { AuthShell } from "@/components/auth/auth-shell";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/features/auth/auth-provider";
import { isApiProblemError } from "@/lib/api/problem";

type FieldErrors = Partial<Record<"email" | "password", string>>;

export default function LoginPage() {
  const auth = useAuth();
  const router = useRouter();
  const [submitting, setSubmitting] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [serverError, setServerError] = useState<{ message: string; traceId?: string } | null>(null);
  const errorRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (serverError) errorRef.current?.focus();
  }, [serverError]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSubmitting(true);
    setFieldErrors({});
    setServerError(null);
    const data = new FormData(event.currentTarget);
    try {
      const result = await auth.login(String(data.get("email")), String(data.get("password")));
      router.push(result.mustChangePassword ? "/change-password" : "/");
    } catch (error) {
      if (isApiProblemError(error)) {
        setFieldErrors(
          Object.fromEntries(
            error.fieldErrors
              .filter(({ field }) => field === "email" || field === "password")
              .map(({ field, message }) => [field, message]),
          ),
        );
        setServerError({ message: error.detail ?? error.title, traceId: error.traceId });
      } else {
        setServerError({ message: "로그인 요청을 완료할 수 없습니다. 잠시 후 다시 시도해 주세요." });
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthShell
      eyebrow="관리자 로그인"
      title="다시 오신 것을 환영합니다"
      description="회사 계정 또는 시스템 관리자 계정으로 로그인해 주세요."
    >
      <Card className="border-slate-200 bg-white shadow-lg shadow-slate-200/60">
        <CardContent>
          <form className="space-y-5" onSubmit={submit} noValidate>
            {serverError ? (
              <Alert ref={errorRef} role="alert" tabIndex={-1} variant="destructive">
                <AlertTitle>로그인할 수 없습니다</AlertTitle>
                <AlertDescription>
                  <p>{serverError.message}</p>
                  {serverError.traceId ? (
                    <p className="mt-1 text-xs">추적 ID: {serverError.traceId}</p>
                  ) : null}
                </AlertDescription>
              </Alert>
            ) : null}
            <div className="space-y-2">
              <Label htmlFor="email">이메일</Label>
              <Input
                id="email"
                name="email"
                type="email"
                autoComplete="username"
                required
                aria-invalid={Boolean(fieldErrors.email)}
                aria-describedby={fieldErrors.email ? "email-error" : undefined}
              />
              {fieldErrors.email ? (
                <p id="email-error" className="text-sm text-destructive">{fieldErrors.email}</p>
              ) : null}
            </div>
            <div className="space-y-2">
              <Label htmlFor="password">비밀번호</Label>
              <Input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                aria-invalid={Boolean(fieldErrors.password)}
                aria-describedby={fieldErrors.password ? "password-error" : undefined}
              />
              {fieldErrors.password ? (
                <p id="password-error" className="text-sm text-destructive">{fieldErrors.password}</p>
              ) : null}
            </div>
            <Button className="w-full" type="submit" disabled={submitting} aria-busy={submitting}>
              {submitting ? "로그인 중…" : "로그인"}
            </Button>
          </form>
        </CardContent>
      </Card>
    </AuthShell>
  );
}
