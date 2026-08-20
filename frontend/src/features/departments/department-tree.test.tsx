import { HttpResponse, http } from "msw";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";

import { server } from "@/test/setup";
import { DepartmentTree } from "./department-tree";

const departments = [
  { id: 1, companyId: 7, parentDepartmentId: null, code: "HQ", name: "본사", status: "ACTIVE", version: 3, createdAt: "2026-08-20T00:00:00Z", updatedAt: "2026-08-20T00:00:00Z" },
  { id: 2, companyId: 7, parentDepartmentId: 1, code: "DEV", name: "개발", status: "ACTIVE", version: 2, createdAt: "2026-08-20T00:00:00Z", updatedAt: "2026-08-20T00:00:00Z" },
  { id: 3, companyId: 7, parentDepartmentId: 2, code: "API", name: "API", status: "ACTIVE", version: 1, createdAt: "2026-08-20T00:00:00Z", updatedAt: "2026-08-20T00:00:00Z" },
] as const;

function departmentPage() {
  server.use(http.get("/api/v1/admin/companies/ACME/departments", () => HttpResponse.json({
    content: departments, page: 0, size: 100, totalElements: 3, totalPages: 1,
  })));
}

it("loads the recursive API hierarchy and disables self and descendants as a parent", async () => {
  departmentPage();
  const user = userEvent.setup();
  render(<DepartmentTree companyCode="acme" />);

  const hq = await screen.findByRole("treeitem", { name: /HQ/ });
  expect(hq).toHaveAttribute("aria-level", "1");
  expect(hq).toHaveAttribute("aria-expanded", "true");
  expect(within(hq).getAllByRole("group")[0]).toContainElement(screen.getByRole("treeitem", { name: /DEV/ }));
  expect(screen.getByRole("treeitem", { name: /API/ })).toHaveAttribute("aria-level", "3");

  await user.click(screen.getByRole("button", { name: "HQ 이동" }));
  expect(screen.getByRole("option", { name: "HQ" })).toBeDisabled();
  expect(screen.getByRole("option", { name: "DEV" })).toBeDisabled();
  expect(screen.getByRole("option", { name: "API" })).toBeDisabled();
  expect(screen.getByRole("option", { name: "최상위 부서" })).toBeEnabled();
});

it("supports tree Arrow, Home, End, Enter, and Space keyboard navigation", async () => {
  departmentPage();
  const user = userEvent.setup();
  render(<DepartmentTree companyCode="ACME" />);
  const hq = await screen.findByRole("treeitem", { name: /HQ/ });
  hq.focus();

  await user.keyboard("{ArrowDown}");
  expect(screen.getByRole("treeitem", { name: /DEV/ })).toHaveFocus();
  await user.keyboard("{End}");
  expect(screen.getByRole("treeitem", { name: /API/ })).toHaveFocus();
  await user.keyboard("{Home}{Enter}");
  expect(hq).toHaveAttribute("aria-selected", "true");
  await user.keyboard(" ");
  expect(hq).toHaveAttribute("aria-expanded", "false");
  await user.keyboard("{ArrowRight}");
  expect(hq).toHaveAttribute("aria-expanded", "true");
});

it("moves to root with the exact versioned DTO and surfaces server 409 authority", async () => {
  departmentPage();
  const user = userEvent.setup();
  let updateCalls = 0;
  server.use(http.put("/api/v1/admin/companies/ACME/departments/DEV", async ({ request }) => {
    updateCalls += 1;
    expect(await request.json()).toEqual({ name: "개발", parentCode: null, status: "ACTIVE", version: 2 });
    return HttpResponse.json({
      type: "https://auth-study.local/problems/invalid-state", title: "Conflict", detail: "활성 하위 부서 또는 사용자 소속을 먼저 해소해 주세요.", status: 409, code: "INVALID_STATE", traceId: "trace-dept-409", fieldErrors: [],
    }, { status: 409 });
  }));

  render(<DepartmentTree companyCode="ACME" />);
  await user.click(await screen.findByRole("treeitem", { name: /DEV/ }));
  await user.click(screen.getByRole("button", { name: "DEV 이동" }));
  await user.selectOptions(screen.getByLabelText("새 상위 부서"), "");
  await user.click(screen.getByRole("button", { name: "이동 저장" }));

  expect(updateCalls).toBe(1);
  expect(await screen.findByText(/활성 하위 부서 또는 사용자 소속/)).toBeVisible();
  expect(screen.getByText(/trace-dept-409/)).toBeVisible();
});
