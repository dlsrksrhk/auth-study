import { beforeEach, describe, expect, it, vi } from "vitest";

import { apiClient } from "@/lib/api/client";
import { dashboardApi } from "./dashboard-api";

vi.mock("@/lib/api/client", () => ({ apiClient: { request: vi.fn() } }));

describe("dashboardApi.summary", () => {
  beforeEach(() => vi.mocked(apiClient.request).mockReset());

  it("전체 콘텐츠 대신 네 목록의 totalElements만 병렬 조회한다", async () => {
    vi.mocked(apiClient.request)
      .mockResolvedValueOnce({ content: [], page: 0, size: 1, totalElements: 11, totalPages: 11 })
      .mockResolvedValueOnce({ content: [], page: 0, size: 1, totalElements: 4, totalPages: 4 })
      .mockResolvedValueOnce({ content: [], page: 0, size: 1, totalElements: 2, totalPages: 2 })
      .mockResolvedValueOnce({ content: [], page: 0, size: 1, totalElements: 3, totalPages: 3 });
    const signal = new AbortController().signal;
    await expect(dashboardApi.summary(" acme ", signal)).resolves.toEqual({ activeUsers: 11, departments: 4, lockedUsers: 2, resignedUsers: 3 });
    expect(apiClient.request).toHaveBeenCalledTimes(4);
    const paths = vi.mocked(apiClient.request).mock.calls.map(([path]) => path);
    expect(paths).toEqual([
      "/api/v1/admin/companies/ACME/users?page=0&size=1&sort=code&status=ACTIVE",
      "/api/v1/admin/companies/ACME/departments?page=0&size=1&sort=code",
      "/api/v1/admin/companies/ACME/users?page=0&size=1&sort=code&status=LOCKED",
      "/api/v1/admin/companies/ACME/users?page=0&size=1&sort=code&status=RESIGNED",
    ]);
    expect(vi.mocked(apiClient.request).mock.calls.every(([, init]) => init?.signal === signal)).toBe(true);
  });
});
