import { apiClient } from "@/lib/api/client";
import type { PageResponse } from "@/features/companies/company-api";

export type Position = {
  id: number;
  companyId: number;
  code: string;
  name: string;
  level: number;
  displayOrder: number;
  active: boolean;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type PositionListParams = {
  page: number;
  size: number;
  sort: "code" | "name" | "level" | "displayOrder";
  search?: string;
  active?: boolean;
};

function collectionPath(companyCode: string, params?: PositionListParams) {
  const base = `/api/v1/admin/companies/${companyCode.trim().toUpperCase()}/positions`;
  if (!params) return base;
  const query = new URLSearchParams({
    page: String(params.page),
    size: String(Math.min(100, Math.max(1, params.size))),
    sort: params.sort,
  });
  if (params.search?.trim()) query.set("search", params.search.trim());
  if (params.active !== undefined) query.set("active", String(params.active));
  return `${base}?${query}`;
}

export const positionApi = {
  list: (companyCode: string, params: PositionListParams, signal?: AbortSignal) =>
    apiClient.request<PageResponse<Position>>(collectionPath(companyCode, params), { signal }),
  create: (companyCode: string, input: { code: string; name: string; level: number; displayOrder: number }) =>
    apiClient.request<Position>(collectionPath(companyCode), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        code: input.code.trim().toUpperCase(),
        name: input.name.trim(),
        level: input.level,
        displayOrder: input.displayOrder,
      }),
    }),
  update: (companyCode: string, positionCode: string, input: { name: string; level: number; displayOrder: number; active: boolean; version: number }) =>
    apiClient.request<Position>(`${collectionPath(companyCode)}/${positionCode.trim().toUpperCase()}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ ...input, name: input.name.trim() }),
    }),
};
