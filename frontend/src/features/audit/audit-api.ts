import type { PageResponse } from "@/features/companies/company-api";
import { apiClient } from "@/lib/api/client";
import { resourceCode } from "@/lib/resource-code";

export type AuditLog = { id: number; actorAccountId: number; action: string; targetType: string; targetId: number; companyId: number | null; success: boolean; occurredAt: string; traceId: string; details: Record<string, unknown> };
export type AuditSort = "occurredAt" | "action" | "success" | "actorAccountId" | "targetType";
export type AuditListParams = { page: number; size: number; sort: AuditSort; search?: string; action?: string; success?: boolean };
export const auditApi = { list(companyCode: string, params: AuditListParams, signal?: AbortSignal) { const query = new URLSearchParams({ page: String(params.page), size: String(params.size), sort: params.sort }); if (params.search?.trim()) query.set("search", params.search.trim()); if (params.action?.trim()) query.set("action", params.action.trim()); if (params.success !== undefined) query.set("success", String(params.success)); return apiClient.request<PageResponse<AuditLog>>(`/api/v1/admin/companies/${resourceCode(companyCode, "회사 코드")}/audit-logs?${query}`, { signal }); } };
