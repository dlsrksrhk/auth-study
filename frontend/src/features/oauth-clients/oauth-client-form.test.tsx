import {
  render,
  screen,
  waitFor,
  fireEvent,
  act,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {beforeEach, expect, it, vi} from "vitest";
import {ApiProblemError} from "@/lib/api/problem";
import {
  OAuthClientCreate,
  OAuthClientForm,
  parseRedirectUris,
} from "./oauth-client-form";
import {oauthClientApi, type OAuthClientDetail} from "./oauth-client-api";

const {push, open, auth} = vi.hoisted(() => ({
  push: vi.fn(),
  open: vi.fn(),
  auth: {
    status: "authenticated",
    actor: {roles: ["COMPANY_ADMIN"], companyCode: "ACME"},
  },
}));
vi.mock("next/navigation", () => ({useRouter: () => ({push})}));
vi.mock("@/features/auth/auth-provider", () => ({useAuth: () => auth}));
vi.mock("./oauth-secret-operation-provider", () => ({
  useOAuthSecretOperations: () => ({open}),
}));
beforeEach(() => {
  vi.restoreAllMocks();
  push.mockReset();
  open.mockReset();
  auth.actor.roles = ["COMPANY_ADMIN"];
  auth.actor.companyCode = "ACME";
});

it.each([
  [" https://app.localhost/cb", "공백"],
  ["https://app.localhost/cb ", "공백"],
  ["https://app.localhost/c b", "공백"],
  ["https://app.localhost/cb\nhttps://app.localhost/cb", "중복"],
  ["https://app.localhost/cb#", "fragment"],
  ["https://user@app.localhost/cb", "사용자 정보"],
  ["https://@app.localhost/cb", "사용자 정보"],
  ["ftp://app.localhost/cb", "http"],
  ["not-a-url", "URI"],
  ["https://app.localhost/cb\n", "빈 줄"],
])("rejects exact URI input %s", (input, message) => {
  expect(() => parseRedirectUris(input, true)).toThrow(message);
});

it("preserves URI bytes and permits empty optional logout URIs", () => {
  expect(
      parseRedirectUris(
          "https://APP.localhost:443/CB?x=%2f\nhttp://dev.localhost/cb",
          true,
      ),
  ).toEqual(["https://APP.localhost:443/CB?x=%2f", "http://dev.localhost/cb"]);
  expect(parseRedirectUris("", false)).toEqual([]);
  expect(() => parseRedirectUris("", true)).toThrow();
});

it("fixes company trust, requires openid and displays field validation without sending", async () => {
  const submit = vi.fn();
  render(<OAuthClientForm canSetTrust={false} onSubmit={submit}/>);
  expect(
      screen.queryByRole("combobox", {name: "신뢰 정책"}),
  ).not.toBeInTheDocument();
  expect(screen.getByText("CONSENT_REQUIRED · 사용자 동의 필요")).toBeVisible();
  expect(screen.getByRole("checkbox", {name: /openid/})).toBeChecked();
  expect(screen.getByRole("checkbox", {name: /openid/})).toBeDisabled();
  fireEvent.change(screen.getByLabelText("표시 이름"), {
    target: {value: "Portal"},
  });
  fireEvent.change(screen.getByLabelText("Redirect URI"), {
    target: {value: " https://app.localhost/cb"},
  });
  await userEvent.click(screen.getByRole("button", {name: "저장"}));
  expect(screen.getByLabelText("Redirect URI")).toHaveAttribute(
      "aria-invalid",
      "true",
  );
  expect(screen.getByLabelText("Redirect URI")).toHaveFocus();
  expect(submit).not.toHaveBeenCalled();
});

it("allows system trust and maps server indexed errors back to fields with trace ID", async () => {
  const submit = vi
      .fn()
      .mockRejectedValue(
          new ApiProblemError({
            type: "about:blank",
            title: "Validation",
            status: 400,
            code: "VALIDATION_ERROR",
            traceId: "trace-form",
            fieldErrors: [
              {
                field: "postLogoutRedirectUris[0]",
                message: "허용되지 않은 주소입니다.",
              },
            ],
          }),
      );
  render(<OAuthClientForm canSetTrust onSubmit={submit}/>);
  fireEvent.change(screen.getByLabelText("표시 이름"), {
    target: {value: "Portal"},
  });
  fireEvent.change(screen.getByLabelText("Redirect URI"), {
    target: {value: "https://APP.localhost:443/CB?x=%2f"},
  });
  await userEvent.selectOptions(
      screen.getByLabelText("신뢰 정책"),
      "TRUSTED_FIRST_PARTY",
  );
  await userEvent.click(screen.getByRole("checkbox", {name: /profile/}));
  await userEvent.click(screen.getByRole("button", {name: "저장"}));
  expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({
        trust: "TRUSTED_FIRST_PARTY",
        scopes: ["openid", "profile"],
        redirectUris: ["https://APP.localhost:443/CB?x=%2f"],
      }),
  );
  expect(await screen.findByText(/trace-form/)).toBeVisible();
  expect(screen.getByLabelText("Post-logout Redirect URI")).toHaveAttribute(
      "aria-describedby",
      "oauth-postLogoutRedirectUris-error",
  );
  await waitFor(() =>
      expect(screen.getByLabelText("Post-logout Redirect URI")).toHaveFocus(),
  );
});

it.each(["OTHER", ""])(
    "denies another company or absent actor company %s",
    (companyCode) => {
      auth.actor.companyCode = companyCode;
      render(<OAuthClientCreate companyCode="ACME"/>);
      expect(
          screen.queryByRole("button", {name: "저장"}),
      ).not.toBeInTheDocument();
      expect(screen.getByRole("alert")).toHaveTextContent("권한");
    },
);

it.each([true, false])(
    "creates public=%s and hands secret to provider before navigation",
    async (publicClient) => {
      const client = {
        clientId: "client/id",
        displayName: "Portal",
        publicClient,
      } as OAuthClientDetail;
      const oneTimeSecret = publicClient ? undefined : crypto.randomUUID();
      const create = vi
          .spyOn(oauthClientApi, "create")
          .mockResolvedValue({client, oneTimeSecret});
      render(<OAuthClientCreate companyCode="acme"/>);
      fireEvent.change(screen.getByLabelText("표시 이름"), {
        target: {value: "Portal"},
      });
      fireEvent.change(screen.getByLabelText("Redirect URI"), {
        target: {value: "http://app.localhost/cb"},
      });
      await userEvent.selectOptions(
          screen.getByLabelText("Client 유형"),
          publicClient ? "public" : "confidential",
      );
      await userEvent.click(screen.getByRole("button", {name: "저장"}));
      await waitFor(() =>
          expect(push).toHaveBeenCalledWith(
              "/companies/ACME/oauth-clients/client%2Fid",
          ),
      );
      expect(create).toHaveBeenCalledWith(
          "ACME",
          expect.objectContaining({publicClient, trust: "CONSENT_REQUIRED"}),
      );
      if (publicClient) expect(open).not.toHaveBeenCalled();
      else {
        expect(open.mock.calls.length).toBe(1);
        expect(open.mock.calls[0][0].oneTimeSecret === oneTimeSecret).toBe(true);
        expect(open.mock.invocationCallOrder[0]).toBeLessThan(
            push.mock.invocationCallOrder[0],
        );
      }
    },
);

it("disables pending submission and ignores late secret responses after leaving the route", async () => {
  let resolve!: (
      result: Awaited<ReturnType<typeof oauthClientApi.create>>,
  ) => void;
  const create = vi.spyOn(oauthClientApi, "create").mockImplementation(
      () =>
          new Promise((done) => {
            resolve = done;
          }),
  );
  const view = render(<OAuthClientCreate companyCode="ACME"/>);
  fireEvent.change(screen.getByLabelText("표시 이름"), {
    target: {value: "Portal"},
  });
  fireEvent.change(screen.getByLabelText("Redirect URI"), {
    target: {value: "http://app.localhost/cb"},
  });
  await userEvent.selectOptions(
      screen.getByLabelText("Client 유형"),
      "confidential",
  );
  await userEvent.click(screen.getByRole("button", {name: "저장"}));
  expect(screen.getByRole("button", {name: "저장 중"})).toBeDisabled();
  expect(screen.getByLabelText("Redirect URI")).toBeDisabled();
  expect(create).toHaveBeenCalledTimes(1);
  view.unmount();
  await act(async () =>
      resolve({
        client: {clientId: "portal", publicClient: false} as OAuthClientDetail,
        oneTimeSecret: crypto.randomUUID(),
      }),
  );
  expect(open).not.toHaveBeenCalled();
  expect(push).not.toHaveBeenCalled();
});

it("supports edit initialization while preventing company-admin trust escalation", async () => {
  const submit = vi.fn().mockResolvedValue(undefined);
  render(
      <OAuthClientForm
          canSetTrust={false}
          clientTypeReadOnly
          initialValues={{
            displayName: "Portal",
            publicClient: false,
            redirectUris: ["https://APP.localhost:443/CB"],
            postLogoutRedirectUris: [],
            scopes: ["email"],
            trust: "TRUSTED_FIRST_PARTY",
          }}
          onSubmit={submit}
      />,
  );
  expect(screen.getByLabelText("Client 유형")).toBeDisabled();
  await userEvent.click(screen.getByRole("button", {name: "저장"}));
  expect(submit).toHaveBeenCalledWith(
      expect.objectContaining({
        publicClient: false,
        redirectUris: ["https://APP.localhost:443/CB"],
        scopes: ["openid", "email"],
        trust: "CONSENT_REQUIRED",
      }),
  );
});
