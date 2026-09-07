import {beforeEach, describe, expect, it, vi} from "vitest";

import {apiClient} from "@/lib/api/client";
import {auditApi} from "./audit-api";

vi.mock("@/lib/api/client", () => ({apiClient: {request: vi.fn()}}));

describe("auditApi.list", () => {
  beforeEach(() => vi.mocked(apiClient.request).mockReset());

  it("회사 범위와 검색·필터·페이징·정렬을 서버에 전달한다", async () => {
    vi.mocked(apiClient.request).mockResolvedValue({content: [], page: 2, size: 50, totalElements: 0, totalPages: 0});
    const signal = new AbortController().signal;
    await auditApi.list(" acme ", {
      page: 2,
      size: 50,
      sort: "action",
      search: " USER ",
      action: "USER_CREATE",
      success: false
    }, signal);
    expect(apiClient.request).toHaveBeenCalledWith("/api/v1/admin/companies/ACME/audit-logs?page=2&size=50&sort=action&search=USER&action=USER_CREATE&success=false", {signal});
  });
});
