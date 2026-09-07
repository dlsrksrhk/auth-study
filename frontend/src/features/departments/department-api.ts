import type {PageResponse} from "@/features/companies/company-api";
import {apiClient} from "@/lib/api/client";
import {resourceCode} from "@/lib/resource-code";

export type DepartmentStatus = "ACTIVE" | "INACTIVE";
export type Department = {
  id: number;
  companyId: number;
  parentDepartmentId: number | null;
  code: string;
  name: string;
  status: DepartmentStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
};

function path(companyCode: string): string {
  return `/api/v1/admin/companies/${resourceCode(companyCode, "회사 코드")}/departments`;
}

async function listAll(companyCode: string, signal?: AbortSignal): Promise<Department[]> {
  const first = await apiClient.request<PageResponse<Department>>(`${path(companyCode)}?page=0&size=100&sort=code`, {signal});
  const pages = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, index) =>
      apiClient.request<PageResponse<Department>>(`${path(companyCode)}?page=${index + 1}&size=100&sort=code`, {signal})));
  return [first, ...pages].flatMap((page) => page.content);
}

export const departmentApi = {
  listAll,
  create: (companyCode: string, input: { code: string; name: string; parentCode: string | null }) =>
      apiClient.request<Department>(path(companyCode), {
        method: "POST", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          code: decodeURIComponent(resourceCode(input.code, "부서 코드")),
          name: input.name.trim(),
          parentCode: input.parentCode ? decodeURIComponent(resourceCode(input.parentCode, "상위 부서 코드")) : null
        }),
      }),
  update: (companyCode: string, departmentCode: string, input: {
    name: string;
    parentCode: string | null;
    status: DepartmentStatus;
    version: number
  }) =>
      apiClient.request<Department>(`${path(companyCode)}/${resourceCode(departmentCode, "부서 코드")}`, {
        method: "PUT", headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          ...input,
          name: input.name.trim(),
          parentCode: input.parentCode ? decodeURIComponent(resourceCode(input.parentCode, "상위 부서 코드")) : null
        }),
      }),
};
