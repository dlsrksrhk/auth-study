import {HttpResponse, http} from "msw";
import {render, screen} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {beforeEach, expect, it, vi} from "vitest";

import {PositionTable} from "./position-table";
import {server} from "@/test/setup";

const replace = vi.fn();
let currentQuery = "";

vi.mock("next/navigation", () => ({
  usePathname: () => "/companies/ACME/positions",
  useRouter: () => ({replace}),
  useSearchParams: () => new URLSearchParams(currentQuery),
}));

beforeEach(() => {
  replace.mockReset();
  currentQuery = "";
});

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
      http.put("/api/v1/admin/companies/ACME/positions/EMPLOYEE", async ({request}) => {
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
            {status: 409},
        );
      }),
  );

  render(<PositionTable companyCode="acme"/>);
  expect(await screen.findByRole("cell", {name: "EMPLOYEE"})).toBeVisible();
  await user.click(screen.getByRole("button", {name: "EMPLOYEE 수정"}));
  expect(screen.getByLabelText("코드")).toHaveAttribute("readonly");
  await user.clear(screen.getByLabelText("직위명"));
  await user.type(screen.getByLabelText("직위명"), "수습 사원");
  await user.click(screen.getByRole("button", {name: "저장"}));

  expect(await screen.findByText("다른 사용자가 수정했습니다.")).toBeVisible();
  expect(screen.getByText(/trace-position-stale/)).toBeVisible();
  await user.click(screen.getByRole("button", {name: "최신 정보 다시 불러오기"}));
  expect(listCalls).toBe(2);
});

it("requires confirmation before deactivating and disables the pending action", async () => {
  const user = userEvent.setup();
  let resolveUpdate!: () => void;
  let current = position;
  let listCalls = 0;
  server.use(
      http.get("/api/v1/admin/companies/ACME/positions", () => {
        listCalls += 1;
        return HttpResponse.json({content: [current], page: 0, size: 20, totalElements: 1, totalPages: 1});
      }),
      http.put("/api/v1/admin/companies/ACME/positions/EMPLOYEE", () =>
          new Promise<Response>((resolve) => {
            resolveUpdate = () => {
              current = {...position, active: false, version: 4};
              resolve(HttpResponse.json(current));
            };
          }),
      ),
  );

  render(<PositionTable companyCode="ACME"/>);
  await screen.findByRole("cell", {name: "EMPLOYEE"});
  await user.click(screen.getByRole("button", {name: "EMPLOYEE 수정"}));
  await user.click(screen.getByRole("checkbox", {name: "활성"}));
  await user.click(screen.getByRole("button", {name: "저장"}));
  expect(screen.getByRole("dialog", {name: "직위를 비활성화할까요?"})).toBeVisible();
  await user.click(screen.getByRole("button", {name: "비활성화"}));
  expect(screen.getByRole("button", {name: "처리 중"})).toBeDisabled();
  resolveUpdate();
  expect(await screen.findByRole("cell", {name: "비활성"})).toBeVisible();
  expect(listCalls).toBe(2);
});

it("removes a deactivated position by re-fetching the current active filter", async () => {
  currentQuery = "active=true";
  const user = userEvent.setup();
  let inactive = false;
  let listCalls = 0;
  server.use(
      http.get("/api/v1/admin/companies/ACME/positions", ({request}) => {
        expect(new URL(request.url).searchParams.get("active")).toBe("true");
        listCalls += 1;
        return HttpResponse.json({
          content: inactive ? [] : [position],
          page: 0,
          size: 20,
          totalElements: inactive ? 0 : 1,
          totalPages: inactive ? 0 : 1
        });
      }),
      http.put("/api/v1/admin/companies/ACME/positions/EMPLOYEE", () => {
        inactive = true;
        return HttpResponse.json({...position, active: false, version: 4});
      }),
  );

  render(<PositionTable companyCode="ACME"/>);
  await screen.findByRole("cell", {name: "EMPLOYEE"});
  await user.click(screen.getByRole("button", {name: "EMPLOYEE 수정"}));
  await user.click(screen.getByRole("checkbox", {name: "활성"}));
  await user.click(screen.getByRole("button", {name: "저장"}));
  await user.click(screen.getByRole("button", {name: "비활성화"}));

  expect(await screen.findByText("등록된 직위가 없습니다.")).toBeVisible();
  expect(screen.queryByRole("cell", {name: "EMPLOYEE"})).not.toBeInTheDocument();
  expect(listCalls).toBe(2);
});

it("re-fetches server order after editing displayOrder", async () => {
  const user = userEvent.setup();
  const manager = {...position, id: 2, code: "MANAGER", name: "과장", level: 30, displayOrder: 20};
  let updated = false;
  server.use(
      http.get("/api/v1/admin/companies/ACME/positions", () => HttpResponse.json({
        content: updated ? [{...manager, displayOrder: 0, version: 4}, position] : [position, manager],
        page: 0, size: 20, totalElements: 2, totalPages: 1,
      })),
      http.put("/api/v1/admin/companies/ACME/positions/MANAGER", async ({request}) => {
        expect(await request.json()).toMatchObject({displayOrder: 0, version: 3});
        updated = true;
        return HttpResponse.json({...manager, displayOrder: 0, version: 4});
      }),
  );

  render(<PositionTable companyCode="ACME"/>);
  await screen.findByRole("cell", {name: "MANAGER"});
  await user.click(screen.getByRole("button", {name: "MANAGER 수정"}));
  await user.clear(screen.getByLabelText("표시 순서"));
  await user.type(screen.getByLabelText("표시 순서"), "0");
  await user.click(screen.getByRole("button", {name: "저장"}));

  const codes = await screen.findAllByRole("cell", {name: /^(EMPLOYEE|MANAGER)$/});
  expect(codes.map((cell) => cell.textContent)).toEqual(["MANAGER", "EMPLOYEE"]);
});

it("does not locally append a create result to a full server page", async () => {
  currentQuery = "page=1";
  const user = userEvent.setup();
  const fullPage = Array.from({length: 20}, (_, index) => ({
    ...position,
    id: index + 1,
    code: `P${String(index).padStart(2, "0")}`,
    name: `직위 ${index}`,
    displayOrder: index,
  }));
  let listCalls = 0;
  server.use(
      http.get("/api/v1/admin/companies/ACME/positions", ({request}) => {
        expect(Object.fromEntries(new URL(request.url).searchParams)).toEqual({
          page: "1",
          size: "20",
          sort: "displayOrder"
        });
        listCalls += 1;
        return HttpResponse.json({content: fullPage, page: 1, size: 20, totalElements: 40, totalPages: 2});
      }),
      http.post("/api/v1/admin/companies/ACME/positions", () => HttpResponse.json({
        ...position, id: 100, code: "NEW", name: "새 직위", displayOrder: 100,
      }, {status: 201})),
  );

  render(<PositionTable companyCode="ACME"/>);
  await screen.findByRole("cell", {name: "P00"});
  await user.click(screen.getByRole("button", {name: "직위 생성"}));
  await user.type(screen.getByLabelText("코드"), "NEW");
  await user.type(screen.getByLabelText("직위명"), "새 직위");
  await user.clear(screen.getByLabelText("표시 순서"));
  await user.type(screen.getByLabelText("표시 순서"), "100");
  await user.click(screen.getByRole("button", {name: "저장"}));

  await vi.waitFor(() => expect(listCalls).toBe(2));
  expect(screen.queryByRole("cell", {name: "NEW"})).not.toBeInTheDocument();
  expect(screen.getByText(/총 40개/)).toBeVisible();
});

it("canonicalizes invalid position query before fetching", async () => {
  currentQuery = "page=1e2&size=101&sort=bogus&active=ACTIVE";
  let listCalls = 0;
  server.use(http.get("/api/v1/admin/companies/ACME/positions", () => {
    listCalls += 1;
    return HttpResponse.json({content: [], page: 0, size: 20, totalElements: 0, totalPages: 0});
  }));

  const view = render(<PositionTable companyCode="ACME"/>);
  await vi.waitFor(() => expect(replace).toHaveBeenCalledWith("/companies/ACME/positions", {scroll: false}));
  expect(listCalls).toBe(0);
  currentQuery = "";
  view.rerender(<PositionTable companyCode="ACME"/>);
  await vi.waitFor(() => expect(listCalls).toBe(1));
});
