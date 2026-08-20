"use client";

import Link from "next/link";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/features/auth/auth-provider";

export default function AccountPage() {
  const auth = useAuth();
  if (auth.status === "loading") {
    return (
      <main className="mx-auto min-h-screen max-w-3xl space-y-4 px-6 py-16">
        <Skeleton className="h-8 w-52" />
        <Skeleton className="h-64 w-full" />
      </main>
    );
  }
  if (auth.status !== "authenticated" || !auth.actor) {
    return (
      <main className="mx-auto flex min-h-screen max-w-lg items-center px-6">
        <Alert>
          <AlertTitle>로그인이 필요합니다</AlertTitle>
          <AlertDescription>
            계정 정보를 보려면 <Link className="underline" href="/login">로그인</Link>해 주세요.
          </AlertDescription>
        </Alert>
      </main>
    );
  }

  const actor = auth.actor;
  return (
    <main className="min-h-screen bg-slate-100 px-6 py-12">
      <div className="mx-auto max-w-3xl">
        <p className="text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">내 계정</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight text-slate-950">계정 정보</h1>
        <Card className="mt-8 border-slate-200 bg-white shadow-sm">
          <CardHeader>
            <CardTitle>{actor.userName ?? actor.email}</CardTitle>
            <CardDescription>{actor.email}</CardDescription>
          </CardHeader>
          <CardContent className="space-y-6">
            <dl className="grid gap-5 sm:grid-cols-2">
              <Detail label="사용자 코드" value={actor.userCode ?? "연결된 HR 사용자 없음"} />
              <Detail label="회사" value={actor.companyCode ?? "시스템"} />
            </dl>
            <div>
              <p className="text-sm font-medium text-slate-500">역할</p>
              <div className="mt-2 flex flex-wrap gap-2">
                {actor.roles.map((role) => <Badge key={role} variant="secondary">{role}</Badge>)}
              </div>
            </div>
            <Button render={<Link href="/change-password" />} variant="outline">비밀번호 변경</Button>
          </CardContent>
        </Card>
      </div>
    </main>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-sm font-medium text-slate-500">{label}</dt>
      <dd className="mt-1 text-sm text-slate-950">{value}</dd>
    </div>
  );
}
