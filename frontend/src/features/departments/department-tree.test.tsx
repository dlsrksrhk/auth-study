import {HttpResponse, http} from "msw";
import {render, screen, waitFor, within} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {expect, it} from "vitest";

import {server} from "@/test/setup";
import {DepartmentTree} from "./department-tree";

const departments = [
  {
    id: 1,
    companyId: 7,
    parentDepartmentId: null,
    code: "HQ",
    name: "본사",
    status: "ACTIVE",
    version: 3,
    createdAt: "2026-08-20T00:00:00Z",
    updatedAt: "2026-08-20T00:00:00Z"
  },
  {
    id: 2,
    companyId: 7,
    parentDepartmentId: 1,
    code: "DEV",
    name: "개발",
    status: "ACTIVE",
    version: 2,
    createdAt: "2026-08-20T00:00:00Z",
    updatedAt: "2026-08-20T00:00:00Z"
  },
  {
    id: 3,
    companyId: 7,
    parentDepartmentId: 2,
    code: "API",
    name: "API",
    status: "ACTIVE",
    version: 1,
    createdAt: "2026-08-20T00:00:00Z",
    updatedAt: "2026-08-20T00:00:00Z"
  },
] as const;

function departmentPage() {
  server.use(http.get("/api/v1/admin/companies/ACME/departments", () => HttpResponse.json({
    content: departments, page: 0, size: 100, totalElements: 3, totalPages: 1,
  })));
}

it("loads the recursive API hierarchy and disables self and descendants as a parent", async () => {
  departmentPage();
  const user = userEvent.setup();
  render(<DepartmentTree companyCode="acme"/>);

  const hq = await screen.findByRole("treeitem", {name: /HQ/});
  expect(hq).toHaveAttribute("aria-level", "1");
  expect(hq).toHaveAttribute("aria-expanded", "true");
  expect(within(hq).getAllByRole("group")[0]).toContainElement(screen.getByRole("treeitem", {name: /DEV/}));
  expect(screen.getByRole("treeitem", {name: /API/})).toHaveAttribute("aria-level", "3");

  await user.click(screen.getByRole("button", {name: "HQ 이동"}));
  expect(screen.getByRole("option", {name: "본사 (HQ)"})).toBeDisabled();
  expect(screen.getByRole("option", {name: "개발 (DEV)"})).toBeDisabled();
  expect(screen.getByRole("option", {name: "API (API)"})).toBeDisabled();
  expect(screen.getByRole("option", {name: "최상위 부서"})).toBeEnabled();
});

it("supports tree Arrow, Home, End, Enter, and Space keyboard navigation", async () => {
  departmentPage();
  const user = userEvent.setup();
  render(<DepartmentTree companyCode="ACME"/>);
  const hq = await screen.findByRole("treeitem", {name: /HQ/});
  hq.focus();

  await user.keyboard("{ArrowDown}");
  expect(screen.getByRole("treeitem", {name: /DEV/})).toHaveFocus();
  expect(screen.getByRole("treeitem", {name: /DEV/})).toHaveAttribute("tabindex", "0");
  expect(hq).toHaveAttribute("tabindex", "-1");
  await user.keyboard("{End}");
  expect(screen.getByRole("treeitem", {name: /API/})).toHaveFocus();
  await user.keyboard("{Home}{Enter}");
  expect(hq).toHaveAttribute("aria-selected", "true");
  await user.keyboard(" ");
  expect(hq).toHaveAttribute("aria-expanded", "false");
  await user.keyboard("{ArrowRight}");
  expect(hq).toHaveAttribute("aria-expanded", "true");
});

it("moves roving focus and selection to the ancestor when a focused descendant is collapsed", async () => {
  departmentPage();
  const user = userEvent.setup();
  render(<DepartmentTree companyCode="ACME"/>);
  const api = await screen.findByRole("treeitem", {name: /API/});
  await user.click(api);
  expect(api).toHaveFocus();
  await user.click(screen.getByRole("button", {name: "HQ 접기"}));

  const hq = screen.getByRole("treeitem", {name: /HQ/});
  expect(hq).toHaveFocus();
  expect(hq).toHaveAttribute("aria-selected", "true");
  expect(hq).toHaveAttribute("tabindex", "0");
  expect(screen.getAllByRole("treeitem").filter((item) => item.tabIndex === 0)).toHaveLength(1);
  expect(screen.getByRole("button", {name: "HQ 펼치기"})).toHaveAttribute("tabindex", "-1");
});

it.each([
  ["중복 ID", [{...departments[0]}, {...departments[1], id: 1}]],
  ["고아 parent", [{...departments[0], parentDepartmentId: 99}]],
  ["순환", [{...departments[0], parentDepartmentId: 2}, {...departments[1], parentDepartmentId: 1}]],
])("blocks an actionable tree for %s graph corruption", async (_label, corrupt) => {
  server.use(http.get("/api/v1/admin/companies/ACME/departments", () => HttpResponse.json({
    content: corrupt,
    page: 0,
    size: 100,
    totalElements: corrupt.length,
    totalPages: 1
  })));
  render(<DepartmentTree companyCode="ACME"/>);

  expect(await screen.findByText(/부서 데이터 무결성 오류/)).toBeVisible();
  expect(screen.queryByRole("tree")).not.toBeInTheDocument();
  expect(screen.getByRole("button", {name: "다시 시도"})).toBeVisible();
});

it("moves to root with the exact versioned DTO and surfaces server 409 authority", async () => {
  departmentPage();
  const user = userEvent.setup();
  let updateCalls = 0;
  server.use(http.put("/api/v1/admin/companies/ACME/departments/DEV", async ({request}) => {
    updateCalls += 1;
    expect(await request.json()).toEqual({name: "개발", parentCode: null, status: "ACTIVE", version: 2});
    return HttpResponse.json({
      type: "https://auth-study.local/problems/invalid-state",
      title: "Conflict",
      detail: "활성 하위 부서 또는 사용자 소속을 먼저 해소해 주세요.",
      status: 409,
      code: "INVALID_STATE",
      traceId: "trace-dept-409",
      fieldErrors: [],
    }, {status: 409});
  }));

  render(<DepartmentTree companyCode="ACME"/>);
  await user.click(await screen.findByRole("treeitem", {name: /DEV/}));
  await user.click(screen.getByRole("button", {name: "DEV 이동"}));
  await user.selectOptions(screen.getByLabelText("새 상위 부서"), "");
  await user.click(screen.getByRole("button", {name: "이동 저장"}));

  expect(updateCalls).toBe(1);
  expect(await screen.findByText(/활성 하위 부서 또는 사용자 소속/)).toBeVisible();
  expect(screen.getByText(/trace-dept-409/)).toBeVisible();
});

it("closes a stale department form and refetches the latest version after an optimistic conflict", async () => {
  const user = userEvent.setup();
  let listCalls = 0;
  server.use(
      http.get("/api/v1/admin/companies/ACME/departments", () => {
        listCalls += 1;
        const content = listCalls === 1 ? departments : departments.map((item) => item.id === 2 ? {
          ...item,
          name: "최신 개발",
          version: 9
        } : item);
        return HttpResponse.json({content, page: 0, size: 100, totalElements: 3, totalPages: 1});
      }),
      http.put("/api/v1/admin/companies/ACME/departments/DEV", async ({request}) => {
        expect(await request.json()).toMatchObject({version: 2});
        return HttpResponse.json({
          type: "about:blank",
          title: "Conflict",
          detail: "Department version does not match.",
          status: 409,
          code: "OPTIMISTIC_LOCK_CONFLICT",
          traceId: "dept-stale",
          fieldErrors: []
        }, {status: 409});
      }),
  );
  render(<DepartmentTree companyCode="ACME"/>);
  await user.click(await screen.findByRole("treeitem", {name: /DEV/}));
  await user.click(screen.getByRole("button", {name: "DEV 수정"}));
  await user.clear(screen.getByLabelText("부서명"));
  await user.type(screen.getByLabelText("부서명"), "내 수정");
  await user.click(screen.getByRole("button", {name: "저장"}));

  expect(await screen.findByText("다른 관리자가 수정해 최신 부서 정보를 다시 불러왔습니다.")).toBeVisible();
  expect(await screen.findByRole("treeitem", {name: "DEV 최신 개발"})).toBeVisible();
  await waitFor(() => expect(screen.getByRole("treeitem", {name: "DEV 최신 개발"})).toHaveFocus());
  expect(screen.queryByRole("dialog", {name: "DEV 수정"})).not.toBeInTheDocument();
  expect(listCalls).toBeGreaterThan(1);
});
