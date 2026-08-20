"use client";

import { use } from "react";

import { DepartmentTree } from "@/features/departments/department-tree";

export default function DepartmentsPage({ params }: { params: Promise<{ companyCode: string }> }) {
  const { companyCode } = use(params);
  return <DepartmentTree companyCode={companyCode} />;
}
