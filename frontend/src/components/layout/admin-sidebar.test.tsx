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
    [["SYSTEM_ADMIN"] as Role[], ["대시보드", "회사", "직위", "부서", "사용자", "감사 로그"]],
    [["COMPANY_ADMIN"] as Role[], ["대시보드", "직위", "부서", "사용자", "감사 로그"]],
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
});
