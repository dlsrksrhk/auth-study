"use client";

import { useEffect, useRef, useState } from "react";
import { Building2, LockKeyhole, UserCheck, UserRoundX } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { isApiProblemError } from "@/lib/api/problem";
import { resourceCode } from "@/lib/resource-code";
import { dashboardApi, type DashboardSummary } from "./dashboard-api";

const cards = [["활성 사용자", "activeUsers", UserCheck], ["부서", "departments", Building2], ["잠금 사용자", "lockedUsers", LockKeyhole], ["퇴사 사용자", "resignedUsers", UserRoundX]] as const;

export function SummaryCards({ companyCode }: { companyCode: string | null }) {
  const [summary, setSummary] = useState<DashboardSummary | null>(null); const [loading, setLoading] = useState(Boolean(companyCode)); const [error, setError] = useState<string | null>(null); const [reload, setReload] = useState(0); const requestId = useRef(0);
  let canonicalCode: string | null = null; try { canonicalCode = companyCode ? decodeURIComponent(resourceCode(companyCode, "회사 코드")) : null; } catch { canonicalCode = null; }
  useEffect(() => {
    if (!canonicalCode) return;
    const id = ++requestId.current; const controller = new AbortController(); queueMicrotask(() => { if (id === requestId.current) { setLoading(true); setError(null); } });
    dashboardApi.summary(canonicalCode, controller.signal).then((next) => { if (id === requestId.current) setSummary(next); }).catch((cause) => { if (id !== requestId.current || (cause instanceof DOMException && cause.name === "AbortError")) return; setSummary(null); setError(isApiProblemError(cause) ? `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})` : cause instanceof Error ? cause.message : "대시보드 요약을 불러오지 못했습니다."); }).finally(() => { if (id === requestId.current) setLoading(false); });
    return () => { controller.abort(); if (requestId.current === id) requestId.current += 1; };
  }, [canonicalCode, reload]);
  if (!companyCode) return <p className="mt-8 rounded-xl border border-dashed bg-white p-10 text-center text-slate-600">관리할 회사를 먼저 선택해 주세요.</p>;
  if (!canonicalCode) return <p aria-live="assertive" className="mt-8 rounded-xl border bg-white p-10 text-center text-red-700">회사 코드가 올바르지 않습니다.</p>;
  if (loading) return <div aria-busy="true" className="mt-8 grid gap-4 sm:grid-cols-2 xl:grid-cols-4"><span className="sr-only">대시보드 요약을 불러오는 중입니다.</span>{cards.map(([label]) => <Skeleton className="h-32" key={label} />)}</div>;
  if (error) return <div className="mt-8 rounded-xl border bg-white p-8 text-center"><p aria-live="assertive" className="text-sm text-red-700">{error}</p><Button className="mt-3" variant="outline" onClick={() => setReload((value) => value + 1)}>다시 시도</Button></div>;
  return <div className="mt-8 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">{cards.map(([label, key, Icon]) => <article className="rounded-xl border bg-white p-5 shadow-sm" key={key}><div className="flex items-center justify-between"><p className="text-sm font-medium text-slate-600">{label}</p><Icon aria-hidden="true" className="size-5 text-teal-700" /></div><p className="mt-4 text-3xl font-semibold tabular-nums">{summary?.[key].toLocaleString()}</p></article>)}</div>;
}
