import {apiClient} from "@/lib/api/client";

export type CompanyStatus = "ACTIVE" | "INACTIVE";

export type Company = {
  id: number;
  code: string;
  name: string;
  emailDomain: string;
  status: CompanyStatus;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type PageResponse<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
};

export type CompanyListParams = {
  page: number;
  size: number;
  sort: "code" | "name" | "status";
  search?: string;
  status?: CompanyStatus;
};

function companyPath(params?: CompanyListParams): string {
  if (!params) return "/api/v1/admin/companies";
  const query = new URLSearchParams({
    page: String(params.page),
    size: String(Math.min(100, Math.max(1, params.size))),
    sort: params.sort,
  });
  if (params.search?.trim()) query.set("search", params.search.trim());
  if (params.status) query.set("status", params.status);
  return `/api/v1/admin/companies?${query}`;
}

export const companyApi = {
  list: (params: CompanyListParams, signal?: AbortSignal) =>
      apiClient.request<PageResponse<Company>>(companyPath(params), {signal}),
  find: (companyCode: string, signal?: AbortSignal) =>
      apiClient.request<Company>(`${companyPath()}/${companyCode.trim().toUpperCase()}`, {signal}),
  create: (input: { code: string; name: string; emailDomain: string }) =>
      apiClient.request<Company>(companyPath(), {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({
          code: input.code.trim().toUpperCase(),
          name: input.name.trim(),
          emailDomain: input.emailDomain.trim().toLowerCase(),
        }),
      }),
  update: (companyCode: string, input: { name: string; status: CompanyStatus; version: number }) =>
      apiClient.request<Company>(`${companyPath()}/${companyCode.trim().toUpperCase()}`, {
        method: "PUT",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({name: input.name.trim(), status: input.status, version: input.version}),
      }),
};
