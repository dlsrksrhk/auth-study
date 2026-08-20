import type { PageResponse } from "@/features/companies/company-api";
import { apiClient } from "@/lib/api/client";
import { resourceCode } from "@/lib/resource-code";

export type DashboardSummary = { activeUsers: number; departments: number; lockedUsers: number; resignedUsers: number };

function base(companyCode: string): string { return `/api/v1/admin/companies/${resourceCode(companyCode, "회사 코드")}`; }
async function total(path: string, signal?: AbortSignal): Promise<number> { return (await apiClient.request<PageResponse<unknown>>(path, { signal })).totalElements; }

export const dashboardApi = {
  async summary(companyCode: string, signal?: AbortSignal): Promise<DashboardSummary> {
    const company = base(companyCode);
    const [activeUsers, departments, lockedUsers, resignedUsers] = await Promise.all([
      total(`${company}/users?page=0&size=1&sort=code&status=ACTIVE`, signal),
      total(`${company}/departments?page=0&size=1&sort=code`, signal),
      total(`${company}/users?page=0&size=1&sort=code&status=LOCKED`, signal),
      total(`${company}/users?page=0&size=1&sort=code&status=RESIGNED`, signal),
    ]);
    return { activeUsers, departments, lockedUsers, resignedUsers };
  },
};
