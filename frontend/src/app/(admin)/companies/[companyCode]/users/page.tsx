"use client";

import {Suspense, use} from "react";

import {Skeleton} from "@/components/ui/skeleton";
import {UserTable} from "@/features/users/user-table";

export default function UsersPage({params}: { params: Promise<{ companyCode: string }> }) {
  const {companyCode} = use(params);
  return <Suspense fallback={<Skeleton className="h-96 w-full"/>}><UserTable companyCode={companyCode}/></Suspense>;
}
