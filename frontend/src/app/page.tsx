"use client";

import Link from "next/link";

import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/features/auth/auth-provider";

export default function Home() {
  const auth = useAuth();
  return (
    <main className="min-h-screen bg-slate-100 px-6 py-16">
      <div className="mx-auto max-w-5xl">
        <p className="text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">Auth Study</p>
        <h1 className="mt-3 text-4xl font-semibold tracking-tight text-slate-950">HR 관리자 워크스페이스</h1>
        <p className="mt-4 max-w-2xl leading-7 text-slate-600">
          인증 주체, 회사와 조직 정보를 관리하는 로컬 학습 환경입니다.
        </p>
        <Card className="mt-10 max-w-xl border-slate-200 bg-white shadow-sm">
          <CardHeader>
            <CardTitle>인증 상태</CardTitle>
            <CardDescription>Access Token은 현재 브라우저 메모리에만 유지됩니다.</CardDescription>
          </CardHeader>
          <CardContent>
            {auth.status === "loading" ? (
              <Skeleton className="h-10 w-full" />
            ) : (
              <div className="flex flex-wrap gap-3">
                {auth.status === "authenticated" ? (
                  <Button render={<Link href="/account" />}>내 계정 보기</Button>
                ) : auth.status === "passwordChangeRequired" ? (
                  <Button render={<Link href="/change-password" />}>비밀번호 변경</Button>
                ) : (
                  <Button render={<Link href="/login" />}>로그인</Button>
                )}
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </main>
  );
}
