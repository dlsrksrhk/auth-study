"use client";

import { Suspense } from "react";

import { Skeleton } from "@/components/ui/skeleton";
import { CompanyTable } from "@/features/companies/company-table";

export default function CompanyPage() {
  return <Suspense fallback={<Skeleton className="h-96 w-full" />}><CompanyTable /></Suspense>;
}
