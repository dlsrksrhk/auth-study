import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {beforeEach, expect, it, vi} from "vitest";
import {ApiProblemError} from "@/lib/api/problem";
import {
  oauthClientApi,
  type OAuthClientDetail as Client,
} from "./oauth-client-api";
import {OAuthClientDetail} from "./oauth-client-detail";
import Page from "@/app/(admin)/companies/[companyCode]/oauth-clients/[clientId]/page";

const {open, auth} = vi.hoisted(() => ({
  open: vi.fn(),
  auth: {
    status: "authenticated",
    actor: {accountId: 1, roles: ["COMPANY_ADMIN"], companyCode: "ACME"},
  },
}));
vi.mock("next/navigation", () => ({useRouter: () => ({})}));
vi.mock("@/features/auth/auth-provider", () => ({useAuth: () => auth}));
vi.mock("./oauth-secret-operation-provider", () => ({
  useOAuthSecretOperations: () => ({open}),
}));
const client: Client = {
  companyCode: "ACME",
  clientId: "portal%2Fid",
  displayName: "Portal",
  status: "ACTIVE",
  trust: "CONSENT_REQUIRED",
  publicClient: true,
  redirectUris: ["https://APP.localhost:443/CB?x=%2f"],
  postLogoutRedirectUris: [],
  scopes: ["openid", "profile"],
  activeSecretHint: null,
  version: 7,
  createdAt: "2026-08-21T00:00:00Z",
  updatedAt: "2026-08-21T00:00:00Z",
};
beforeEach(() => {
  vi.restoreAllMocks();
  open.mockReset();
  auth.status = "authenticated";
  auth.actor.roles = ["COMPANY_ADMIN"];
  auth.actor.companyCode = "ACME";
  vi.spyOn(oauthClientApi, "get").mockResolvedValue(client);
  vi.spyOn(oauthClientApi, "listConsents").mockResolvedValue({
    content: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
  });
  vi.spyOn(oauthClientApi, "update").mockResolvedValue({
    ...client,
    version: 8,
  });
  vi.spyOn(oauthClientApi, "disable").mockResolvedValue();
  vi.spyOn(oauthClientApi, "enable").mockResolvedValue();
  vi.spyOn(oauthClientApi, "revokeSecret").mockResolvedValue({
    ...client,
    publicClient: false,
    version: 8,
  });
});

async function show(overrides: Partial<Client> = {}) {
  vi.mocked(oauthClientApi.get).mockResolvedValue({...client, ...overrides});
  const view = render(
      <OAuthClientDetail companyCode="ACME" clientId={client.clientId}/>,
  );
  await screen.findByRole("heading", {name: "Portal"});
  return view;
}

async function confirm(label: string) {
  await userEvent.click(
      screen.getByRole("button", {name: label}),
  );
  const dialog = screen.getByRole("dialog");
  expect(dialog).toHaveAccessibleDescription();
  await userEvent.click(within(dialog).getByRole("button", {name: `${label} 확인`}));
}

it("shows none as the authentication method for a public client", async () => {
  await show({publicClient: true});
  expect(screen.getByText("인증 방식: none")).toBeVisible();
});
it("shows client_secret_basic as the authentication method for a confidential client", async () => {
  await show({publicClient: false});
  expect(screen.getByText("인증 방식: client_secret_basic")).toBeVisible();
});
it("fixes client_id, copies with a value-free live announcement, and hides public secret actions", async () => {
  const user = userEvent.setup();
  const copy = vi.spyOn(navigator.clipboard, "writeText").mockResolvedValue();
  await show();
  expect(
      screen.queryByRole("button", {name: /secret/}),
  ).not.toBeInTheDocument();
  expect(screen.getByLabelText("client_id")).toHaveAttribute("readonly");
  await user.click(screen.getByRole("button", {name: "client_id 복사"}));
  expect(copy).toHaveBeenCalledWith(client.clientId);
  expect(screen.getByRole("status")).toHaveTextContent(
      "client_id를 복사했습니다.",
  );
  expect(screen.getByRole("status")).not.toHaveTextContent(client.clientId);
});
it("confirms disable revocation, sends expected version, and reloads detail", async () => {
  await show({publicClient: false});
  expect(screen.getByRole("button", {name: "secret 회전"})).toBeVisible();
  expect(screen.getByRole("button", {name: "secret 폐기"})).toBeVisible();
  await userEvent.click(
      screen.getByRole("button", {name: "비활성화"}),
  );
  expect(screen.getByRole("dialog")).toHaveTextContent(
      /활성 authorization과 refresh.*폐기/,
  );
  expect(oauthClientApi.disable).not.toHaveBeenCalled();
  vi.mocked(oauthClientApi.get).mockResolvedValue({
    ...client,
    status: "DISABLED",
    version: 8,
  });
  await userEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {name: "비활성화 확인"}),
  );
  await screen.findByRole("button", {name: "활성화"});
  expect(oauthClientApi.disable).toHaveBeenCalledWith(
      "ACME",
      client.clientId,
      7,
  );
  expect(oauthClientApi.get).toHaveBeenCalledTimes(2);
  expect(
      screen.queryByRole("button", {name: /secret/}),
  ).not.toBeInTheDocument();
});
it("enables an inactive client with the current version", async () => {
  await show({publicClient: false, status: "DISABLED"});
  expect(
      screen.queryByRole("button", {name: /secret/}),
  ).not.toBeInTheDocument();
  await confirm("활성화");
  await waitFor(() =>
      expect(oauthClientApi.enable).toHaveBeenCalledWith(
          "ACME",
          client.clientId,
          7,
      ),
  );
  await waitFor(() => expect(oauthClientApi.get).toHaveBeenCalledTimes(2));
});
it("reuses exact URI and scope editing, omits immutable type, sends version", async () => {
  await show();
  expect(screen.getByLabelText("Client 유형")).toBeDisabled();
  fireEvent.change(screen.getByLabelText("표시 이름"), {
    target: {value: "Updated"},
  });
  await userEvent.click(screen.getByRole("checkbox", {name: /email/}));
  await userEvent.click(screen.getByRole("button", {name: "저장"}));
  await waitFor(() =>
      expect(oauthClientApi.update).toHaveBeenCalledWith(
          "ACME",
          client.clientId,
          {
            displayName: "Updated",
            redirectUris: client.redirectUris,
            postLogoutRedirectUris: [],
            scopes: ["openid", "profile", "email"],
            trust: "CONSENT_REQUIRED",
            version: 7,
          },
      ),
  );
});
it("blocks invalid redirects before update", async () => {
  await show();
  fireEvent.change(screen.getByLabelText("Redirect URI"), {
    target: {value: " https://app.localhost"},
  });
  await userEvent.click(screen.getByRole("button", {name: "저장"}));
  expect(screen.getByLabelText("Redirect URI")).toHaveAttribute(
      "aria-invalid",
      "true",
  );
  expect(oauthClientApi.update).not.toHaveBeenCalled();
});
it("offers conflict reload with trace ID and replaces stale edits", async () => {
  vi.mocked(oauthClientApi.update).mockRejectedValue(
      new ApiProblemError({
        type: "about:blank",
        title: "Conflict",
        status: 409,
        code: "OPTIMISTIC_LOCK_CONFLICT",
        traceId: "trace-conflict",
        fieldErrors: [],
      }),
  );
  await show();
  await userEvent.click(screen.getByRole("button", {name: "저장"}));
  expect(await screen.findByText(/최신 상세 정보를 다시 불러/)).toBeVisible();
  expect(
      screen
          .getAllByRole("alert")
          .some((node) => node.textContent?.includes("trace-conflict")),
  ).toBe(true);
  vi.mocked(oauthClientApi.get).mockResolvedValue({
    ...client,
    displayName: "Latest",
    version: 9,
  });
  await userEvent.click(
      screen.getByRole("button", {name: "최신 정보 불러오기"}),
  );
  await waitFor(() =>
      expect(screen.getByLabelText("표시 이름")).toHaveValue("Latest"),
  );
});
it("hands rotation directly to provider after explicit revocation confirmation", async () => {
  const secret = crypto.randomUUID();
  const rotate = vi
      .spyOn(oauthClientApi, "rotateSecret")
      .mockResolvedValue({
        client: {...client, publicClient: false},
        oneTimeSecret: secret,
      });
  await show({publicClient: false});
  await userEvent.click(screen.getByRole("button", {name: "secret 회전"}));
  expect(screen.getByRole("dialog")).toHaveTextContent(
      /기존 secret.*활성 authorization과 refresh.*폐기/,
  );
  await userEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {name: "secret 회전 확인"}),
  );
  await waitFor(() => expect(open).toHaveBeenCalledTimes(1));
  expect(rotate).toHaveBeenCalledWith("ACME", client.clientId);
  expect(open.mock.calls[0][0].oneTimeSecret === secret).toBe(true);
  expect(document.body.textContent?.includes(secret)).toBe(false);
});
it("revokes secret only after confirmation and receives an ordinary detail", async () => {
  await show({publicClient: false});
  await userEvent.click(screen.getByRole("button", {name: "secret 폐기"}));
  expect(screen.getByRole("dialog")).toHaveTextContent(/새 secret.*회전/);
  await userEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", {name: "취소"}),
  );
  expect(oauthClientApi.revokeSecret).not.toHaveBeenCalled();
  await confirm("secret 폐기");
  await waitFor(() =>
      expect(oauthClientApi.revokeSecret).toHaveBeenCalledWith(
          "ACME",
          client.clientId,
      ),
  );
  expect(open).not.toHaveBeenCalled();
});
it.each([
  ["USER", "ACME"],
  ["COMPANY_ADMIN", "OTHER"],
])("denies %s in %s before API fetch", async (role, company) => {
  auth.actor.roles = [role];
  auth.actor.companyCode = company;
  render(<OAuthClientDetail companyCode="ACME" clientId={client.clientId}/>);
  expect(screen.getByRole("alert")).toHaveTextContent("권한이 없습니다");
  expect(oauthClientApi.get).not.toHaveBeenCalled();
});
it("keeps company-admin trusted settings read-only while permitting status operations", async () => {
  await show({trust: "TRUSTED_FIRST_PARTY"});
  expect(screen.getByText(/시스템 관리자만.*수정/)).toBeVisible();
  expect(
      screen.queryByRole("button", {name: "저장"}),
  ).not.toBeInTheDocument();
  expect(screen.getByText("TRUSTED_FIRST_PARTY")).toBeVisible();
  expect(screen.getByRole("button", {name: "비활성화"})).toBeVisible();
});
it("permits system-admin trust edits across companies", async () => {
  auth.actor.roles = ["SYSTEM_ADMIN"];
  auth.actor.companyCode = "OTHER";
  await show({trust: "TRUSTED_FIRST_PARTY"});
  expect(screen.getByLabelText("신뢰 정책")).toHaveValue("TRUSTED_FIRST_PARTY");
  expect(screen.getByRole("button", {name: "저장"})).toBeEnabled();
});
it("discards late rotation after leaving the detail", async () => {
  let finish!: (
      value: Awaited<ReturnType<typeof oauthClientApi.rotateSecret>>,
  ) => void;
  vi.spyOn(oauthClientApi, "rotateSecret").mockImplementation(
      () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
  );
  const view = await show({publicClient: false});
  await confirm("secret 회전");
  view.unmount();
  await act(async () => finish({client, oneTimeSecret: crypto.randomUUID()}));
  expect(open).not.toHaveBeenCalled();
});
it("awaits route params without decoding the opaque client ID", async () => {
  const element = await Page({
    params: Promise.resolve({companyCode: "ACME", clientId: client.clientId}),
  });
  expect(element.props).toEqual({
    companyCode: "ACME",
    clientId: client.clientId,
  });
});

it("shows initial API failure trace and retries without exposing mutation controls", async () => {
  vi.mocked(oauthClientApi.get).mockRejectedValueOnce(
      new ApiProblemError({
        type: "about:blank",
        title: "Not found",
        status: 404,
        code: "RESOURCE_NOT_FOUND",
        traceId: "trace-load",
        fieldErrors: [],
      }),
  );
  render(<OAuthClientDetail companyCode="ACME" clientId={client.clientId}/>);
  expect(await screen.findByRole("alert")).toHaveTextContent("trace-load");
  expect(
      screen.queryByRole("button", {name: "저장"}),
  ).not.toBeInTheDocument();
  await userEvent.click(
      screen.getByRole("button", {name: "최신 정보 불러오기"}),
  );
  await screen.findByRole("heading", {name: "Portal"});
});

it("disables stale mutations on status conflict until the latest detail is loaded", async () => {
  vi.mocked(oauthClientApi.disable).mockRejectedValue(
      new ApiProblemError({
        type: "about:blank",
        title: "Conflict",
        status: 409,
        code: "OPTIMISTIC_LOCK_CONFLICT",
        traceId: "trace-status",
        fieldErrors: [],
      }),
  );
  await show();
  await confirm("비활성화");
  expect(await screen.findByRole("alert")).toHaveTextContent("trace-status");
  expect(screen.getByRole("button", {name: "저장"})).toBeDisabled();
  expect(screen.getByRole("button", {name: "비활성화"})).toBeDisabled();
  vi.mocked(oauthClientApi.get).mockResolvedValue({...client, version: 9});
  await userEvent.click(
      screen.getByRole("button", {name: "최신 정보 불러오기"}),
  );
  await waitFor(() =>
      expect(screen.getByRole("button", {name: "저장"})).toBeEnabled(),
  );
  vi.mocked(oauthClientApi.disable).mockResolvedValue();
  await confirm("비활성화");
  await waitFor(() =>
      expect(oauthClientApi.disable).toHaveBeenLastCalledWith(
          "ACME",
          client.clientId,
          9,
      ),
  );
});

it("prevents duplicate mutations while confirmation is pending", async () => {
  let finish!: () => void;
  vi.mocked(oauthClientApi.disable).mockImplementation(
      () =>
          new Promise<void>((resolve) => {
            finish = resolve;
          }),
  );
  await show();
  await confirm("비활성화");
  expect(screen.getByRole("button", {name: "비활성화 확인"})).toBeDisabled();
  fireEvent.click(screen.getByRole("button", {name: "비활성화 확인"}));
  expect(oauthClientApi.disable).toHaveBeenCalledTimes(1);
  await act(async () => finish());
});

it("hides old detail immediately when actor loses company access", async () => {
  const view = await show();
  auth.actor.companyCode = "OTHER";
  view.rerender(
      <OAuthClientDetail companyCode="ACME" clientId={client.clientId}/>,
  );
  expect(screen.getByRole("alert")).toHaveTextContent("권한이 없습니다");
  expect(screen.queryByLabelText("client_id")).not.toBeInTheDocument();
});
