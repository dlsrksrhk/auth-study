import { HttpResponse, http } from "msw";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";

import { server } from "@/test/setup";
import type { Department } from "@/features/departments/department-api";
import { MembershipEditor } from "./membership-editor";
import { UserDetail } from "./user-detail";
import { UserForm } from "./user-form";

const departments: Department[] = [
  { id: 1, companyId: 7, parentDepartmentId: null, code: "DEV", name: "개발", status: "ACTIVE", version: 1, createdAt: "2026-08-20T00:00:00Z", updatedAt: "2026-08-20T00:00:00Z" },
  { id: 2, companyId: 7, parentDepartmentId: null, code: "API", name: "API", status: "ACTIVE", version: 1, createdAt: "2026-08-20T00:00:00Z", updatedAt: "2026-08-20T00:00:00Z" },
];
const userRecord = { id: 10, companyId: 7, code: "U001", employeeNumber: "E001", name: "홍길동", loginEmail: "u001@acme.test", phone: "010-0000-0000", hiredAt: "2026-08-20", workplace: "서울", profileImageUrl: "", positionId: 5, status: "PENDING", version: 4, createdAt: "2026-08-20T00:00:00Z", updatedAt: "2026-08-20T00:00:00Z" } as const;
const memberships = [
  { id: 31, companyId: 7, userId: 10, departmentId: 1, role: "MEMBER", primary: false, startedAt: "2026-08-20T00:00:00Z", endedAt: null, version: 2 },
  { id: 32, companyId: 7, userId: 10, departmentId: 2, role: "DEPUTY_HEAD", primary: false, startedAt: "2026-08-20T00:00:00Z", endedAt: null, version: 1 },
] as const;

it("requires exactly one active primary membership and active position before activation", async () => {
  const user = userEvent.setup();
  let activationCalls = 0;
  server.use(http.put("/api/v1/admin/companies/ACME/users/U001/status", () => {
    activationCalls += 1;
    return HttpResponse.json({ ...userRecord, status: "ACTIVE", version: 5 });
  }));
  render(<MembershipEditor companyCode="ACME" departments={departments} memberships={[...memberships]} positionActive={false} user={userRecord} onRefresh={() => undefined} />);

  await user.click(screen.getByRole("button", { name: "사용자 활성화" }));
  expect(screen.getByText("주 소속을 하나 지정해 주세요.")).toBeVisible();
  expect(screen.getByText("활성 직위를 지정해 주세요.")).toBeVisible();
  expect(activationCalls).toBe(0);
});

it("switches primary atomically through one update request and keeps active history separate", async () => {
  const user = userEvent.setup();
  const withPrimary = [{ ...memberships[0], primary: true }, memberships[1]];
  let updateCalls = 0;
  server.use(http.put("/api/v1/admin/companies/ACME/users/U001/memberships/32", async ({ request }) => {
    updateCalls += 1;
    expect(await request.json()).toEqual({ role: "DEPUTY_HEAD", primary: true, version: 1 });
    return HttpResponse.json({ ...memberships[1], primary: true, version: 2 });
  }));
  render(<MembershipEditor companyCode="ACME" departments={departments} memberships={[...withPrimary, { ...memberships[0], id: 21, primary: false, endedAt: "2026-08-19T00:00:00Z" }]} positionActive user={{ ...userRecord, status: "ACTIVE" }} onRefresh={() => undefined} />);

  expect(screen.getByRole("region", { name: "종료된 소속 이력" })).toHaveTextContent("종료");
  await user.click(screen.getByRole("button", { name: "API를 주 소속으로 지정" }));
  expect(updateCalls).toBe(1);
});

it("prevents ending the sole primary of an active user and surfaces HEAD conflict", async () => {
  const user = userEvent.setup();
  const withPrimary = [{ ...memberships[0], primary: true }];
  let endCalls = 0;
  server.use(
    http.delete("/api/v1/admin/companies/ACME/users/U001/memberships/31", () => { endCalls += 1; return HttpResponse.json({}); }),
    http.put("/api/v1/admin/companies/ACME/users/U001/memberships/31", () => HttpResponse.json({
      type: "https://auth-study.local/problems/invalid-state", title: "Conflict", detail: "Department already has an active head.", status: 409, code: "INVALID_STATE", traceId: "head-conflict", fieldErrors: [],
    }, { status: 409 })),
  );
  render(<MembershipEditor companyCode="ACME" departments={departments} memberships={withPrimary} positionActive user={{ ...userRecord, status: "ACTIVE" }} onRefresh={() => undefined} />);

  await user.click(screen.getByRole("button", { name: "DEV 소속 종료" }));
  expect(await screen.findByText("활성 사용자의 유일한 주 소속은 종료하거나 일반 소속으로 변경할 수 없습니다.")).toBeVisible();
  expect(endCalls).toBe(0);
  await user.selectOptions(screen.getByLabelText("DEV 역할"), "HEAD");
  expect(await screen.findByText(/이미 활성 부서장이 있습니다/)).toBeVisible();
  expect(screen.getByText(/head-conflict/)).toBeVisible();
});

it("refreshes server state after an optimistic membership version conflict", async () => {
  const user = userEvent.setup();
  const refresh = vi.fn();
  server.use(http.put("/api/v1/admin/companies/ACME/users/U001/memberships/32", () => HttpResponse.json({
    type: "https://auth-study.local/problems/optimistic-lock-conflict", title: "Conflict", detail: "Membership version does not match.", status: 409, code: "OPTIMISTIC_LOCK_CONFLICT", traceId: "membership-stale", fieldErrors: [],
  }, { status: 409 })));
  render(<MembershipEditor companyCode="ACME" departments={departments} memberships={[{ ...memberships[0], primary: true }, memberships[1]]} positionActive user={{ ...userRecord, status: "ACTIVE" }} onRefresh={refresh} />);

  await user.click(screen.getByRole("button", { name: "API를 주 소속으로 지정" }));
  expect(await screen.findByText(/다른 관리자가 소속을 수정했습니다/)).toBeVisible();
  expect(screen.getByText(/membership-stale/)).toBeVisible();
  expect(refresh).toHaveBeenCalledOnce();
});

it("shows a creation password once and clears it permanently when the dialog closes", async () => {
  const user = userEvent.setup();
  server.use(http.post("/api/v1/admin/companies/ACME/users", async ({ request }) => {
    expect(await request.json()).toEqual({ code: "U001", employeeNumber: "E001", name: "홍길동", loginEmail: "u001@acme.test", phone: "010", hiredAt: "2026-08-20", workplace: "서울", profileImageUrl: "", positionCode: "EMPLOYEE" });
    return HttpResponse.json({ user: userRecord, temporaryPassword: "OnlyOnce1234!" }, { status: 201 });
  }));
  render(<UserForm companyCode="ACME" onRefresh={() => undefined} open positions={[{ id: 5, companyId: 7, code: "EMPLOYEE", name: "사원", level: 10, displayOrder: 10, active: true, version: 1, createdAt: "", updatedAt: "" }]} onOpenChange={() => undefined} />);
  await user.type(screen.getByLabelText("사용자 코드"), "u001");
  await user.type(screen.getByLabelText("사번"), "E001");
  await user.type(screen.getByLabelText("이름"), "홍길동");
  await user.type(screen.getByLabelText("로그인 이메일"), "u001@acme.test");
  await user.type(screen.getByLabelText("전화번호"), "010");
  await user.type(screen.getByLabelText("입사일"), "2026-08-20");
  await user.type(screen.getByLabelText("근무지"), "서울");
  await user.selectOptions(screen.getByLabelText("직위"), "EMPLOYEE");
  await user.click(screen.getByRole("button", { name: "사용자 생성" }));
  expect(await screen.findByText("OnlyOnce1234!")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "비밀번호 확인 완료" }));
  expect(screen.queryByText("OnlyOnce1234!")).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /다시 보기/ })).not.toBeInTheDocument();
});

it("renders an XSS-shaped name as text and surfaces peer-admin 403 from role-sensitive controls", async () => {
  const user = userEvent.setup();
  const xssName = '<img src=x onerror=alert(1)>';
  server.use(
    http.get("/api/v1/admin/companies/ACME/users/U001", () => HttpResponse.json({ ...userRecord, name: xssName })),
    http.get("/api/v1/admin/companies/ACME/positions", () => HttpResponse.json({ content: [{ id: 5, companyId: 7, code: "EMPLOYEE", name: "사원", level: 10, displayOrder: 10, active: true, version: 1, createdAt: "", updatedAt: "" }], page: 0, size: 100, totalElements: 1, totalPages: 1 })),
    http.get("/api/v1/admin/companies/ACME/departments", () => HttpResponse.json({ content: departments, page: 0, size: 100, totalElements: 2, totalPages: 1 })),
    http.get("/api/v1/admin/companies/ACME/users/U001/memberships", () => HttpResponse.json({ content: memberships, page: 0, size: 100, totalElements: 2, totalPages: 1 })),
    http.post("/api/v1/admin/companies/ACME/users/U001/temporary-password", () => HttpResponse.json({ type: "about:blank", title: "Forbidden", detail: "관리자 계정은 시스템 관리자만 변경할 수 있습니다.", status: 403, code: "FORBIDDEN", traceId: "peer-admin-403", fieldErrors: [] }, { status: 403 })),
  );
  render(<UserDetail actorRoles={["COMPANY_ADMIN"]} companyCode="ACME" userCode="U001" />);
  expect((await screen.findAllByText(xssName))[0]).toBeVisible();
  expect(document.querySelector("img")).toBeNull();
  expect(screen.queryByRole("button", { name: "회사 관리자 지정" })).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "임시 비밀번호 재발급" }));
  expect(await screen.findByText(/관리자 계정은 시스템 관리자만/)).toBeVisible();
  expect(screen.getByText(/peer-admin-403/)).toBeVisible();
});
