"use client";

import {useSearchParams} from "next/navigation";
import {useAuth} from "@/features/auth/auth-provider";
import {SummaryCards} from "@/features/dashboard/summary-cards";

export default function AdminHomePage() {
  const {actor} = useAuth();
  const searchParams = useSearchParams();
  const companyCode = actor?.roles.includes("SYSTEM_ADMIN") ? searchParams.get("companyCode") : actor?.companyCode ?? null;
  return (
      <section aria-labelledby="dashboard-title" className="mx-auto max-w-6xl">
        <p className="text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">Workspace</p>
        <h1 className="mt-2 text-3xl font-semibold tracking-tight text-slate-950" id="dashboard-title">관리자 대시보드</h1>
        <p className="mt-2 text-sm text-slate-600">선택한 회사의 현재 HR 현황입니다.</p>
        <SummaryCards companyCode={companyCode}/>
      </section>
  );
}
