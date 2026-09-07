import {HttpResponse, http} from "msw";
import {act, render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {beforeEach, expect, it} from "vitest";
import {StrictMode, useState} from "react";

import {authSession} from "@/features/auth/auth-session";
import {server} from "@/test/setup";
import {AdminSecretOperationProvider, useAdminSecretOperations} from "./admin-secret-operation-provider";

const input = {
  code: "U001",
  employeeNumber: "E001",
  name: "홍길동",
  loginEmail: "u001@acme.test",
  phone: "010",
  hiredAt: "2026-08-20",
  workplace: "서울",
  profileImageUrl: "",
  positionCode: "EMPLOYEE"
};
const createdUser = {
  id: 10,
  companyId: 7,
  code: "U001",
  employeeNumber: "E001",
  name: "홍길동",
  loginEmail: "u001@acme.test",
  roles: ["USER"],
  phone: "010",
  hiredAt: "2026-08-20",
  workplace: "서울",
  profileImageUrl: "",
  positionId: 5,
  status: "PENDING",
  version: 0,
  createdAt: "",
  updatedAt: ""
};

beforeEach(() => {
  authSession.set("admin-token", "authenticated");
});

function Initiator({kind}: { kind: "create" | "reset" }) {
  const operations = useAdminSecretOperations();
  return <button type="button"
                 onClick={() => void (kind === "create" ? operations.createUser("ACME", input) : operations.resetPassword("ACME", "U001", "u001@acme.test"))}>작업
    시작</button>;
}

function RouteHarness({kind}: { kind: "create" | "reset" }) {
  const [route, setRoute] = useState<"user" | "department">("user");
  return <AdminSecretOperationProvider>
    <button type="button" onClick={() => setRoute("department")}>부서 route로 이동</button>
    {route === "user" ? <Initiator kind={kind}/> : <p>부서 route</p>}</AdminSecretOperationProvider>;
}

it.each(["create", "reset"] as const)("keeps a committed %s secret operation alive across an admin child route switch", async (kind) => {
  const user = userEvent.setup();
  let committed = false;
  let requestSignal: AbortSignal | undefined;
  let release: (() => void) | undefined;
  const path = kind === "create" ? "/api/v1/admin/companies/ACME/users" : "/api/v1/admin/companies/ACME/users/U001/temporary-password";
  server.use(http.post(path, async ({request}) => {
    requestSignal = request.signal;
    committed = true;
    await new Promise<void>((resolve) => {
      release = resolve;
    });
    return HttpResponse.json(kind === "create" ? {
      user: createdUser,
      temporaryPassword: "RouteSafeCreate123!"
    } : {temporaryPassword: "RouteSafeReset123!"}, {status: kind === "create" ? 201 : 200});
  }));
  render(<RouteHarness kind={kind}/>);

  await user.click(screen.getByRole("button", {name: "작업 시작"}));
  await waitFor(() => expect(committed).toBe(true));
  await user.click(screen.getByRole("button", {name: "부서 route로 이동"}));
  expect(screen.getByText("부서 route")).toBeVisible();
  expect(requestSignal?.aborted).toBe(false);
  act(() => release?.());

  const secret = kind === "create" ? "RouteSafeCreate123!" : "RouteSafeReset123!";
  expect(await screen.findByText(secret)).toBeVisible();
  expect(screen.getByRole("dialog")).toHaveAccessibleName(kind === "create" ? "사용자 생성 임시 비밀번호" : "임시 비밀번호 재발급");
  expect(screen.getByRole("dialog")).toHaveTextContent("ACME");
  expect(screen.getByRole("dialog")).toHaveTextContent("U001");
  expect(screen.getByRole("dialog")).toHaveTextContent("u001@acme.test");
  await user.click(screen.getByRole("button", {name: "비밀번호 확인 완료"}));
  expect(screen.queryByText(secret)).not.toBeInTheDocument();
  expect(screen.queryByRole("button", {name: /다시 보기/})).not.toBeInTheDocument();
});

it("aborts pending work and clears a displayed secret when the provider ends or logout occurs", async () => {
  const user = userEvent.setup();
  let requestSignal: AbortSignal | undefined;
  let release: (() => void) | undefined;
  server.use(http.post("/api/v1/admin/companies/ACME/users", async ({request}) => {
    requestSignal = request.signal;
    await new Promise<void>((resolve) => {
      release = resolve;
    });
    return HttpResponse.json({user: createdUser, temporaryPassword: "DisposeMe123!"}, {status: 201});
  }));
  const view = render(<AdminSecretOperationProvider><Initiator kind="create"/></AdminSecretOperationProvider>);
  await user.click(screen.getByRole("button", {name: "작업 시작"}));
  await waitFor(() => expect(requestSignal).toBeDefined());
  view.unmount();
  expect(requestSignal?.aborted).toBe(true);
  act(() => release?.());

  server.use(http.post("/api/v1/admin/companies/ACME/users/U001/temporary-password", () => HttpResponse.json({temporaryPassword: "LogoutClear123!"})));
  render(<AdminSecretOperationProvider><Initiator kind="reset"/></AdminSecretOperationProvider>);
  await user.click(screen.getByRole("button", {name: "작업 시작"}));
  expect(await screen.findByText("LogoutClear123!")).toBeVisible();
  act(() => {
    authSession.clear();
  });
  await waitFor(() => expect(screen.queryByText("LogoutClear123!")).not.toBeInTheDocument());
});

it("keeps the provider owner active after the Strict Mode effect lifecycle probe", async () => {
  const user = userEvent.setup();
  server.use(http.post("/api/v1/admin/companies/ACME/users/U001/temporary-password", () => HttpResponse.json({temporaryPassword: "StrictOwner123!"})));
  render(<StrictMode><AdminSecretOperationProvider><Initiator
      kind="reset"/></AdminSecretOperationProvider></StrictMode>);
  await user.click(screen.getByRole("button", {name: "작업 시작"}));
  expect(await screen.findByText("StrictOwner123!")).toBeVisible();
});

it.each(["resolve", "reject"] as const)("ignores a delayed clipboard %s from a cleared secret after a new secret arrives", async (outcome) => {
  const user = userEvent.setup();
  let finishCopy: (() => void) | undefined;
  const clipboardResult = new Promise<void>((resolve, reject) => {
    finishCopy = () => outcome === "resolve" ? resolve() : reject(new Error("clipboard failed"));
  });
  Object.defineProperty(navigator, "clipboard", {configurable: true, value: {writeText: () => clipboardResult}});
  server.use(
      http.post("/api/v1/admin/companies/ACME/users/U001/temporary-password", () => HttpResponse.json({temporaryPassword: "FirstSecret123!"})),
      http.post("/api/v1/admin/companies/ACME/users/U002/temporary-password", () => HttpResponse.json({temporaryPassword: "SecondSecret123!"})),
  );

  function ClipboardHarness() {
    const operations = useAdminSecretOperations();
    return <>
      <button onClick={() => void operations.resetPassword("ACME", "U001", "first@acme.test")}>첫 재발급</button>
      <button onClick={() => void operations.resetPassword("ACME", "U002", "second@acme.test")}>둘째 재발급</button>
    </>;
  }

  render(<AdminSecretOperationProvider><ClipboardHarness/></AdminSecretOperationProvider>);

  await user.click(screen.getByRole("button", {name: "첫 재발급"}));
  expect(await screen.findByText("FirstSecret123!")).toBeVisible();
  await user.click(screen.getByRole("button", {name: "복사"}));
  await user.click(screen.getByRole("button", {name: "비밀번호 확인 완료"}));
  await user.click(screen.getByRole("button", {name: "둘째 재발급"}));
  expect(await screen.findByText("SecondSecret123!")).toBeVisible();
  act(() => finishCopy?.());

  await waitFor(() => {
    expect(screen.queryByText("임시 비밀번호를 복사했습니다.")).not.toBeInTheDocument();
    expect(screen.queryByText("직접 선택해 복사해 주세요.")).not.toBeInTheDocument();
  });
  expect(screen.getByRole("dialog")).toHaveTextContent("U002");
  expect(screen.getByRole("dialog")).toHaveTextContent("second@acme.test");
});
