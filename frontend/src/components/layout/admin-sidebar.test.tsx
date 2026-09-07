import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { AdminSidebar } from "./admin-sidebar";
import type { Role } from "@/features/auth/auth-api";

function renderSidebar(roles: Role[], companyCode: string | null = null) {
  render(
    <AdminSidebar
      roles={roles}
      companyCode={companyCode}
      currentPath="/"
    />,
  );
}

describe("AdminSidebar", () => {
  it.each([
    [["SYSTEM_ADMIN"] as Role[], ["대시보드", "회사", "직위", "부서", "사용자", "감사 로그", "인증/인가 설정"]],
    [["COMPANY_ADMIN"] as Role[], ["대시보드", "직위", "부서", "사용자", "감사 로그", "인증/인가 설정"]],
    [["USER"] as Role[], ["내 계정"]],
  ])("shows only allowed navigation for %s", (roles, labels) => {
    renderSidebar(roles, roles[0] === "SYSTEM_ADMIN" ? "ZEN" : "ACME");

    expect(screen.getAllByRole("link")).toHaveLength(labels.length);
    labels.forEach((label) => {
      expect(screen.getByRole("link", { name: label })).toBeVisible();
    });
    if (roles[0] !== "SYSTEM_ADMIN") {
      expect(screen.queryByRole("link", { name: "회사" })).not.toBeInTheDocument();
    }
    if (roles[0] === "USER") {
      expect(screen.queryByRole("link", { name: "사용자" })).not.toBeInTheDocument();
    }
  });

  it("uses the selected company code in organization links", () => {
    renderSidebar(["SYSTEM_ADMIN"], "zen");

    expect(screen.getByRole("link", { name: "직위" })).toHaveAttribute(
      "href",
      "/companies/ZEN/positions",
    );
    expect(screen.getByRole("link", { name: "부서" })).toHaveAttribute(
      "href",
      "/companies/ZEN/departments",
    );
  });

  it.each(["SYSTEM_ADMIN", "COMPANY_ADMIN"] as const)("scopes OAuth navigation for %s and marks nested routes active", (role) => {
    render(<AdminSidebar roles={[role]} companyCode="acme" currentPath="/companies/ACME/oauth-clients/client/protocol-events" />);
    const link = screen.getByRole("link", { name: "인증/인가 설정" });
    expect(link).toHaveAttribute("href", "/companies/ACME/oauth-clients");
    expect(link).toHaveAttribute("aria-current", "page");
    expect(link.querySelector("svg")).toHaveClass("lucide-shield-keyhole");
  });

  it("guides system admins to select a company before OAuth administration", () => {
    renderSidebar(["SYSTEM_ADMIN"]);
    expect(screen.getByRole("link", { name: "인증/인가 설정" })).toHaveAttribute("href", "/companies");
    expect(screen.getByText("인증/인가 설정을 관리하려면 먼저 회사를 선택해 주세요.")).toBeVisible();
  });
});
