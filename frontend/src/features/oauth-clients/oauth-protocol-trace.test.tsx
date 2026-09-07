import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { oauthClientApi, type ProtocolEvent } from "./oauth-client-api";
import { OAuthProtocolTrace } from "./oauth-protocol-trace";
import Page from "@/app/(admin)/companies/[companyCode]/oauth-clients/[clientId]/protocol-events/page";

const { auth, nav } = vi.hoisted(() => ({
  auth: {
    status: "authenticated",
    actor: { accountId: 1, roles: ["COMPANY_ADMIN"], companyCode: "ACME" },
  },
  nav: { query: "", push: vi.fn() },
}));
vi.mock("@/features/auth/auth-provider", () => ({ useAuth: () => auth }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(nav.query),
  useRouter: () => ({ push: nav.push }),
}));
const event: ProtocolEvent = {
  id: 1,
  occurredAt: "2026-08-21T00:00:00Z",
  eventType: "AUTHORIZATION_CODE_EXCHANGED",
  outcome: "SUCCESS",
  correlationId: "correlation-1",
  clientId: "portal",
  subject: "12345678-abcd-4321-9876-abcdef123456",
  authorizationId: null,
  errorCode: null,
  metadata: {
    endpoint: "TOKEN",
    grant_type: "AUTHORIZATION_CODE",
    http_status: 200,
  },
};
const page = { content: [event], nextCursor: "opaque-cursor", hasNext: true };
beforeEach(() => {
  vi.restoreAllMocks();
  nav.query = "";
  nav.push.mockReset();
  auth.status = "authenticated";
  auth.actor = { accountId: 1, roles: ["COMPANY_ADMIN"], companyCode: "ACME" };
  vi.spyOn(oauthClientApi, "listProtocolEvents").mockResolvedValue(page);
});
it("renders canonical trace fields and rejects unknown keys and invalid allowlisted values", async () => {
  vi.mocked(oauthClientApi.listProtocolEvents).mockResolvedValue({
    ...page,
    content: [
      {
        ...event,
        metadata: {
          ...event.metadata,
          password: "hidden-password",
          access_token: "hidden-token",
          client_secret: "hidden-secret",
          code_verifier: "hidden-verifier",
          extra: { secret: "hidden-nested" },
          reason: "hidden-in-allowed-key",
          scopes: ["openid", "hidden-scope"],
        },
      },
    ],
  });
  const { container } = render(
    <OAuthProtocolTrace companyCode="ACME" clientId="portal" />,
  );
  expect(await screen.findByText("correlation-1")).toBeVisible();
  expect(screen.getByText("12345678…3456")).toBeVisible();
  expect(screen.getByText("TOKEN")).toBeVisible();
  expect(screen.getAllByText("redacted").length).toBeGreaterThan(0);
  expect(container.innerHTML).not.toContain("hidden-");
  expect(container.innerHTML).not.toContain(event.subject!);
});
it("reads cursor filters from URL, navigates next, and drops cursor when a filter changes", async () => {
  nav.query =
    "type=REFRESH_ROTATED&outcome=SUCCESS&cursor=previous&page=9&secret=discard";
  render(<OAuthProtocolTrace companyCode="ACME" clientId="portal" />);
  await screen.findByText("correlation-1");
  expect(oauthClientApi.listProtocolEvents).toHaveBeenCalledWith(
    "ACME",
    "portal",
    {
      size: 50,
      cursor: "previous",
      type: "REFRESH_ROTATED",
      outcome: "SUCCESS",
    },
    expect.any(AbortSignal),
  );
  await userEvent.click(screen.getByRole("button", { name: "다음 이벤트" }));
  expect(nav.push).toHaveBeenLastCalledWith(
    "/companies/ACME/oauth-clients/portal/protocol-events?type=REFRESH_ROTATED&outcome=SUCCESS&cursor=opaque-cursor",
    { scroll: false },
  );
  await userEvent.selectOptions(screen.getByLabelText("결과"), "DENIED");
  expect(nav.push).toHaveBeenLastCalledWith(
    "/companies/ACME/oauth-clients/portal/protocol-events?type=REFRESH_ROTATED&outcome=DENIED",
    { scroll: false },
  );
});
it("discards late events when URL filters change", async () => {
  let resolve!: (value: typeof page) => void;
  vi.mocked(oauthClientApi.listProtocolEvents).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done;
    }),
  );
  const view = render(
    <OAuthProtocolTrace companyCode="ACME" clientId="portal" />,
  );
  await waitFor(() =>
    expect(oauthClientApi.listProtocolEvents).toHaveBeenCalled(),
  );
  nav.query = "outcome=DENIED";
  vi.mocked(oauthClientApi.listProtocolEvents).mockResolvedValue({
    content: [],
    hasNext: false,
    nextCursor: null,
  });
  view.rerender(<OAuthProtocolTrace companyCode="ACME" clientId="portal" />);
  await screen.findByText("조건에 맞는 protocol 이벤트가 없습니다.");
  await act(async () => resolve(page));
  expect(screen.queryByText("correlation-1")).not.toBeInTheDocument();
});
it.each(["OTHER", "ACME"])("blocks tenant or role mismatch: %s", (company) => {
  if (company === "ACME") auth.actor.roles = ["EMPLOYEE"];
  render(<OAuthProtocolTrace companyCode={company} clientId="portal" />);
  expect(screen.getByRole("alert")).toBeVisible();
  expect(oauthClientApi.listProtocolEvents).not.toHaveBeenCalled();
});
it("clears each filter, keeps canonical options, and returns to first cursor", async () => {
  nav.query = "type=REFRESH_ROTATED&outcome=SUCCESS&cursor=older";
  render(<OAuthProtocolTrace companyCode="ACME" clientId="portal" />);
  await screen.findByText("correlation-1");
  expect(
    screen
      .getAllByRole("option")
      .some((option) => option.getAttribute("value") === "TOKEN_ISSUED"),
  ).toBe(false);
  await userEvent.selectOptions(screen.getByLabelText("이벤트 유형"), "");
  expect(nav.push).toHaveBeenLastCalledWith(
    "/companies/ACME/oauth-clients/portal/protocol-events?outcome=SUCCESS",
    { scroll: false },
  );
  await userEvent.selectOptions(screen.getByLabelText("결과"), "");
  expect(nav.push).toHaveBeenLastCalledWith(
    "/companies/ACME/oauth-clients/portal/protocol-events?type=REFRESH_ROTATED",
    { scroll: false },
  );
  await userEvent.click(screen.getByRole("button", { name: "첫 이벤트" }));
  expect(nav.push).toHaveBeenLastCalledWith(
    "/companies/ACME/oauth-clients/portal/protocol-events?type=REFRESH_ROTATED&outcome=SUCCESS",
    { scroll: false },
  );
});
it("renders awaited route params without double decoding and permits system admins", async () => {
  auth.actor.roles = ["SYSTEM_ADMIN"];
  render(
    await Page({
      params: Promise.resolve({
        companyCode: "OTHER",
        clientId: "portal%2Fid",
      }),
    }),
  );
  await screen.findByText("correlation-1");
  expect(oauthClientApi.listProtocolEvents).toHaveBeenCalledWith(
    "OTHER",
    "portal%2Fid",
    expect.any(Object),
    expect.any(AbortSignal),
  );
  expect(screen.getByRole("link", { name: "client 상세" })).toHaveAttribute(
    "href",
    "/companies/OTHER/oauth-clients/portal%252Fid",
  );
});
it("has safe retryable errors and disables navigation without more events", async () => {
  vi.mocked(oauthClientApi.listProtocolEvents).mockRejectedValueOnce(
    new Error("sensitive-error"),
  );
  render(<OAuthProtocolTrace companyCode="ACME" clientId="portal" />);
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "불러오지 못했습니다",
  );
  expect(document.body).not.toHaveTextContent("sensitive-error");
  vi.mocked(oauthClientApi.listProtocolEvents).mockResolvedValue({
    content: [],
    nextCursor: null,
    hasNext: false,
  });
  await userEvent.click(
    screen.getByRole("button", { name: "이벤트 다시 시도" }),
  );
  await screen.findByText("조건에 맞는 protocol 이벤트가 없습니다.");
  expect(screen.getByRole("button", { name: "다음 이벤트" })).toBeDisabled();
});
it("clears loaded events on tenant denial and ignores late responses after actor replacement", async () => {
  let resolve!: (value: typeof page) => void;
  vi.mocked(oauthClientApi.listProtocolEvents).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done;
    }),
  );
  const view = render(
    <OAuthProtocolTrace companyCode="ACME" clientId="portal" />,
  );
  await waitFor(() =>
    expect(oauthClientApi.listProtocolEvents).toHaveBeenCalled(),
  );
  auth.actor.accountId = 2;
  vi.mocked(oauthClientApi.listProtocolEvents).mockResolvedValue({
    content: [],
    hasNext: false,
    nextCursor: null,
  });
  view.rerender(<OAuthProtocolTrace companyCode="ACME" clientId="portal" />);
  await screen.findByText("조건에 맞는 protocol 이벤트가 없습니다.");
  await act(async () => resolve(page));
  expect(screen.queryByText("correlation-1")).not.toBeInTheDocument();
  auth.actor.companyCode = "OTHER";
  view.rerender(<OAuthProtocolTrace companyCode="ACME" clientId="portal" />);
  expect(screen.getByRole("alert")).toBeVisible();
  expect(screen.queryByRole("heading")).not.toBeInTheDocument();
});
