import { HttpResponse, http } from "msw";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";

import { PositionTable } from "./position-table";
import { server } from "@/test/setup";

const replace = vi.fn();

vi.mock("next/navigation", () => ({
  usePathname: () => "/companies/ACME/positions",
  useRouter: () => ({ replace }),
  useSearchParams: () => new URLSearchParams(),
}));

const position = {
  id: 1,
  companyId: 8,
  code: "EMPLOYEE",
  name: "사원",
  level: 10,
  displayOrder: 10,
  active: true,
  version: 3,
  createdAt: "2026-08-20T01:00:00Z",
  updatedAt: "2026-08-20T01:00:00Z",
};

it("keeps code readonly and recovers from a stale position update", async () => {
  const user = userEvent.setup();
  let listCalls = 0;
  server.use(
    http.get("/api/v1/admin/companies/ACME/positions", () => {
      listCalls += 1;
      return HttpResponse.json({
        content: [position], page: 0, size: 20, totalElements: 1, totalPages: 1,
      });
    }),
    http.put("/api/v1/admin/companies/ACME/positions/EMPLOYEE", async ({ request }) => {
      expect(await request.json()).toEqual({
        name: "수습 사원",
        level: 10,
        displayOrder: 10,
        active: true,
        version: 3,
      });
      return HttpResponse.json(
        {
          type: "https://auth-study.local/problems/optimistic-lock-conflict",
          title: "Conflict",
          status: 409,
          code: "OPTIMISTIC_LOCK_CONFLICT",
          traceId: "trace-position-stale",
          fieldErrors: [],
        },
        { status: 409 },
      );
    }),
  );

  render(<PositionTable companyCode="acme" />);
  expect(await screen.findByRole("cell", { name: "EMPLOYEE" })).toBeVisible();
  await user.click(screen.getByRole("button", { name: "EMPLOYEE 수정" }));
  expect(screen.getByLabelText("코드")).toHaveAttribute("readonly");
  await user.clear(screen.getByLabelText("직위명"));
  await user.type(screen.getByLabelText("직위명"), "수습 사원");
  await user.click(screen.getByRole("button", { name: "저장" }));

  expect(await screen.findByText("다른 사용자가 수정했습니다.")).toBeVisible();
  expect(screen.getByText(/trace-position-stale/)).toBeVisible();
  await user.click(screen.getByRole("button", { name: "최신 정보 다시 불러오기" }));
  expect(listCalls).toBe(2);
});

it("requires confirmation before deactivating and disables the pending action", async () => {
  const user = userEvent.setup();
  let resolveUpdate!: () => void;
  server.use(
    http.get("/api/v1/admin/companies/ACME/positions", () =>
      HttpResponse.json({ content: [position], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
    ),
    http.put("/api/v1/admin/companies/ACME/positions/EMPLOYEE", () =>
      new Promise<Response>((resolve) => {
        resolveUpdate = () => resolve(HttpResponse.json({ ...position, active: false, version: 4 }));
      }),
    ),
  );

  render(<PositionTable companyCode="ACME" />);
  await screen.findByRole("cell", { name: "EMPLOYEE" });
  await user.click(screen.getByRole("button", { name: "EMPLOYEE 수정" }));
  await user.click(screen.getByRole("checkbox", { name: "활성" }));
  await user.click(screen.getByRole("button", { name: "저장" }));
  expect(screen.getByRole("dialog", { name: "직위를 비활성화할까요?" })).toBeVisible();
  await user.click(screen.getByRole("button", { name: "비활성화" }));
  expect(screen.getByRole("button", { name: "처리 중" })).toBeDisabled();
  resolveUpdate();
  expect(await screen.findByRole("cell", { name: "비활성" })).toBeVisible();
});
