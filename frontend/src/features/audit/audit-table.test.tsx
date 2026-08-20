import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { auditApi } from "./audit-api";
import { AuditTable } from "./audit-table";

const replace = vi.fn();
let query = "";
vi.mock("next/navigation", () => ({
  usePathname: () => "/companies/ACME/audit-logs",
  useRouter: () => ({ replace }),
  useSearchParams: () => new URLSearchParams(query),
}));
vi.mock("./audit-api", () => ({ auditApi: { list: vi.fn() } }));

describe("AuditTable", () => {
  beforeEach(() => {
    query = "";
    replace.mockReset();
    vi.mocked(auditApi.list).mockReset();
    vi.mocked(auditApi.list).mockResolvedValue({
      content: [{ id: 1, actorAccountId: 42, action: "USER_CREATE", targetType: "USER", targetId: 99, companyId: 7, success: true, occurredAt: "2026-08-20T01:02:03Z", traceId: "trace-1", details: { userCode: "<img src=x onerror=alert(1)>" } }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
  });

  it("감사 필드를 읽기 전용 표로 안전하게 표시한다", async () => {
    render(<AuditTable companyCode="ACME" />);
    expect(await screen.findByText("USER_CREATE")).toBeInTheDocument();
    expect(screen.getByText("계정 42")).toBeInTheDocument();
    expect(screen.getByText("USER #99")).toBeInTheDocument();
    expect(screen.getByText("trace-1")).toBeInTheDocument();
    expect(screen.getByText(/<img src=x/)).toBeInTheDocument();
    expect(document.querySelector("img")).toBeNull();
    expect(screen.queryByRole("button", { name: /수정|삭제/ })).not.toBeInTheDocument();
  });

  it("잘못된 URL 쿼리를 canonical query로 교체한다", () => {
    query = "page=-1&size=999&sort=nope&success=maybe&extra=x";
    render(<AuditTable companyCode="ACME" />);
    expect(replace).toHaveBeenCalledWith("/companies/ACME/audit-logs", { scroll: false });
    expect(auditApi.list).not.toHaveBeenCalled();
  });

  it("성공 여부 필터를 URL에 기록한다", async () => {
    render(<AuditTable companyCode="ACME" />);
    await screen.findByText("USER_CREATE");
    fireEvent.change(screen.getByLabelText("성공 여부"), { target: { value: "false" } });
    expect(replace).toHaveBeenLastCalledWith("/companies/ACME/audit-logs?success=false", { scroll: false });
  });
});
