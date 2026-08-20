"use client";

import { Suspense, use } from "react";
import { Skeleton } from "@/components/ui/skeleton";
import { AuditTable } from "@/features/audit/audit-table";

export default function AuditLogsPage({ params }: { params: Promise<{ companyCode: string }> }) {
  const { companyCode } = use(params);
  return <Suspense fallback={<Skeleton className="h-96 w-full" />}><AuditTable companyCode={companyCode} /></Suspense>;
}
