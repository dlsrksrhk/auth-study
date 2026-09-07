"use client";

import {Suspense, use} from "react";

import {Skeleton} from "@/components/ui/skeleton";
import {PositionTable} from "@/features/positions/position-table";

export default function PositionsPage({params}: { params: Promise<{ companyCode: string }> }) {
  const {companyCode} = use(params);
  return <Suspense fallback={<Skeleton className="h-96 w-full"/>}><PositionTable companyCode={companyCode}/></Suspense>;
}
