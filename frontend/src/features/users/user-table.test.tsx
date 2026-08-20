import { HttpResponse, http } from "msw";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { useState } from "react";

import { server } from "@/test/setup";
import { UserTable } from "./user-table";
import { AdminSecretOperationProvider } from "./admin-secret-operation-provider";

const replace = vi.fn();
let currentQuery = "";

vi.mock("next/navigation", () => ({
  usePathname: () => "/companies/ACME/users",
  useRouter: () => ({ replace }),
  useSearchParams: () => new URLSearchParams(currentQuery),
}));

beforeEach(() => { replace.mockReset(); currentQuery = ""; });

const position = { id: 5, companyId: 7, code: "EMPLOYEE", name: "사원", level: 10, displayOrder: 10, active: true, version: 1, createdAt: "", updatedAt: "" };
const createdUser = { id: 10, companyId: 7, code: "U001", employeeNumber: "E001", name: "홍길동", loginEmail: "u001@acme.test", phone: "010", hiredAt: "2026-08-20", workplace: "서울", profileImageUrl: "", positionId: 5, status: "PENDING", version: 0, createdAt: "", updatedAt: "", roles: ["USER"] };

function listHandlers() {
  server.use(
    http.get("/api/v1/admin/companies/ACME/users", () => HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
    http.get("/api/v1/admin/companies/ACME/positions", () => HttpResponse.json({ content: [position], page: 0, size: 100, totalElements: 1, totalPages: 1 })),
  );
}

function renderAdmin(ui: React.ReactNode) { return render(<AdminSecretOperationProvider>{ui}</AdminSecretOperationProvider>); }

it("keeps the create form mounted through the one-time password step then clears it before parent close", async () => {
  listHandlers();
  const user = userEvent.setup();
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", { configurable: true, value: { writeText } });
  server.use(http.post("/api/v1/admin/companies/ACME/users", () => HttpResponse.json({ user: createdUser, temporaryPassword: "ParentLives123!" }, { status: 201 })));

  renderAdmin(<UserTable companyCode="ACME" />);
  await screen.findByText("등록된 사용자가 없습니다.");
  await user.click(screen.getByRole("button", { name: "사용자 생성" }));
  await user.type(screen.getByLabelText("사용자 코드"), "U001");
  await user.type(screen.getByLabelText("사번"), "E001");
  await user.type(screen.getByLabelText("이름"), "홍길동");
  await user.type(screen.getByLabelText("로그인 이메일"), "u001@acme.test");
  await user.type(screen.getByLabelText("전화번호"), "010");
  await user.type(screen.getByLabelText("입사일"), "2026-08-20");
  await user.type(screen.getByLabelText("근무지"), "서울");
  await user.selectOptions(screen.getByLabelText("직위"), "EMPLOYEE");
  await user.click(screen.getByRole("button", { name: "사용자 생성" }));

  expect(await screen.findByText("ParentLives123!")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "복사" }));
  expect(writeText).toHaveBeenCalledWith("ParentLives123!");
  await user.click(screen.getByRole("button", { name: "비밀번호 확인 완료" }));
  expect(screen.queryByText("ParentLives123!")).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "사용자 생성" }));
  expect(screen.queryByText("ParentLives123!")).not.toBeInTheDocument();
  expect(screen.getByRole("dialog", { name: "사용자 생성" })).toBeVisible();
});

it("keeps the same focused search input through self commit and external back navigation", async () => {
  currentQuery = "search=a";
  listHandlers();
  const user = userEvent.setup();
  const view = renderAdmin(<UserTable companyCode="ACME" />);
  await screen.findByText("등록된 사용자가 없습니다.");
  const input = screen.getByLabelText("사용자 검색");
  await user.click(input);
  await user.type(input, "b");
  expect(input).toHaveValue("ab");
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/companies/ACME/users?search=ab", { scroll: false }));

  currentQuery = "search=ab";
  view.rerender(<AdminSecretOperationProvider><UserTable companyCode="ACME" /></AdminSecretOperationProvider>);
  expect(screen.getByLabelText("사용자 검색")).toBe(input);
  expect(input).toHaveFocus();
  await user.type(input, "c");
  expect(input).toHaveValue("abc");
  currentQuery = "search=a";
  view.rerender(<AdminSecretOperationProvider><UserTable companyCode="ACME" /></AdminSecretOperationProvider>);

  await waitFor(() => expect(input).toHaveValue("a"));
  expect(screen.getByLabelText("사용자 검색")).toBe(input);
  expect(input).toHaveFocus();
  await user.type(input, "z");
  expect(input).toHaveValue("az");
});

it("sends canonical server paging, search, status, and sort query values", async () => {
  currentQuery = "search=kim&status=LOCKED&page=2&size=50&sort=name";
  let observed = "";
  server.use(
    http.get("/api/v1/admin/companies/ACME/users", ({ request }) => { observed = new URL(request.url).searchParams.toString(); return HttpResponse.json({ content: [], page: 2, size: 50, totalElements: 0, totalPages: 0 }); }),
    http.get("/api/v1/admin/companies/ACME/positions", () => HttpResponse.json({ content: [position], page: 0, size: 100, totalElements: 1, totalPages: 1 })),
  );
  renderAdmin(<UserTable companyCode="ACME" />);
  await screen.findByText("등록된 사용자가 없습니다.");
  expect(observed).toBe("page=2&size=50&sort=name&search=kim&status=LOCKED");
  expect(screen.getByLabelText("사용자 검색")).toHaveValue("kim");
  expect(screen.getByLabelText("상태")).toHaveValue("LOCKED");
  expect(screen.getByLabelText("정렬")).toHaveValue("name");
});

it("does not mistake a later external query for an earlier trimmed self commit", async () => {
  currentQuery = "search=a";
  listHandlers();
  const user = userEvent.setup();
  const view = renderAdmin(<UserTable companyCode="ACME" />);
  const input = await screen.findByLabelText("사용자 검색");
  await user.click(input);
  await user.type(input, " ");
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/companies/ACME/users?search=a", { scroll: false }));

  currentQuery = "search=b";
  view.rerender(<AdminSecretOperationProvider><UserTable companyCode="ACME" /></AdminSecretOperationProvider>);
  await waitFor(() => expect(input).toHaveValue("b"));
  currentQuery = "search=a";
  view.rerender(<AdminSecretOperationProvider><UserTable companyCode="ACME" /></AdminSecretOperationProvider>);
  await waitFor(() => expect(input).toHaveValue("a"));
  expect(input).toHaveFocus();
});

it("keeps a delayed committed create alive when the user page route child unmounts", async () => {
  listHandlers();
  const user = userEvent.setup();
  let requestSignal: AbortSignal | undefined;
  let release: (() => void) | undefined;
  server.use(http.post("/api/v1/admin/companies/ACME/users", async ({ request }) => {
    requestSignal = request.signal;
    await new Promise<void>((resolve) => { release = resolve; });
    return HttpResponse.json({ user: createdUser, temporaryPassword: "MustNotSurface123!" }, { status: 201 });
  }));
  function Routes() {
    const [route, setRoute] = useState<"users" | "departments">("users");
    return <AdminSecretOperationProvider><button type="button" onClick={() => setRoute("departments")}>부서 관리로 이동</button>{route === "users" ? <UserTable companyCode="ACME" /> : <p>부서 관리 route</p>}</AdminSecretOperationProvider>;
  }
  render(<Routes />);
  await screen.findByText("등록된 사용자가 없습니다.");
  await user.click(screen.getByRole("button", { name: "사용자 생성" }));
  await user.type(screen.getByLabelText("사용자 코드"), "U001");
  await user.type(screen.getByLabelText("사번"), "E001");
  await user.type(screen.getByLabelText("이름"), "홍길동");
  await user.type(screen.getByLabelText("로그인 이메일"), "u001@acme.test");
  await user.type(screen.getByLabelText("전화번호"), "010");
  await user.type(screen.getByLabelText("입사일"), "2026-08-20");
  await user.type(screen.getByLabelText("근무지"), "서울");
  await user.selectOptions(screen.getByLabelText("직위"), "EMPLOYEE");
  await user.click(screen.getByRole("button", { name: "사용자 생성" }));
  await waitFor(() => expect(requestSignal).toBeDefined());
  fireEvent.click(screen.getByText("부서 관리로 이동"));
  expect(screen.getByText("부서 관리 route")).toBeVisible();
  expect(requestSignal?.aborted).toBe(false);
  release?.();
  expect(await screen.findByText("MustNotSurface123!")).toBeVisible();
});
