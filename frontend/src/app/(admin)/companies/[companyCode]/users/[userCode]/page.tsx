"use client";

import { use } from "react";

import { useAuth } from "@/features/auth/auth-provider";
import { UserDetail } from "@/features/users/user-detail";

export default function UserDetailPage({ params }: { params: Promise<{ companyCode: string; userCode: string }> }) {
  const { companyCode, userCode } = use(params);
  const { actor } = useAuth();
  return <UserDetail actorRoles={actor?.roles ?? []} companyCode={companyCode} userCode={userCode} />;
}
