import {render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {beforeEach, expect, it, vi} from "vitest";
import {OAuthClientTable} from "./oauth-client-table";
import {oauthClientApi, type OAuthClientSummary} from "./oauth-client-api";

const {replace, state} = vi.hoisted(() => ({
  replace: vi.fn(),
  state: {query: "", roles: ["COMPANY_ADMIN"], companyCode: "ACME"},
}));
vi.mock("next/navigation", () => ({
  useRouter: () => ({replace}),
  usePathname: () => "/companies/ACME/oauth-clients",
  useSearchParams: () => new URLSearchParams(state.query),
}));
vi.mock("@/features/auth/auth-provider", () => ({
  useAuth: () => ({status: "authenticated", actor: state}),
}));
beforeEach(() => {
  vi.restoreAllMocks();
  replace.mockReset();
  state.query = "";
  state.roles = ["COMPANY_ADMIN"];
  state.companyCode = "ACME";
});
const client = {
  companyCode: "ACME",
  clientId: "portal",
  displayName: "Portal",
  publicClient: true,
  status: "ACTIVE",
  trust: "CONSENT_REQUIRED",
  scopes: ["openid", "profile"],
  updatedAt: "2026-08-21T00:00:00Z",
} as OAuthClientSummary;

it("loads own company paginated list and links create and detail", async () => {
  const list = vi
      .spyOn(oauthClientApi, "list")
      .mockResolvedValue({
        content: [client],
        page: 0,
        size: 20,
        totalElements: 21,
        totalPages: 2,
      });
  render(<OAuthClientTable companyCode="acme"/>);
  expect(await screen.findByRole("cell", {name: "Portal"})).toBeVisible();
  expect(list).toHaveBeenCalledWith(
      "ACME",
      {page: 0, size: 20},
      expect.any(AbortSignal),
  );
  expect(
      screen.getByRole("link", {name: "OAuth client 생성"}),
  ).toHaveAttribute("href", "/companies/ACME/oauth-clients/new");
  expect(screen.getByRole("link", {name: "Portal 상세"})).toHaveAttribute(
      "href",
      "/companies/ACME/oauth-clients/portal",
  );
  for (const label of [
    "client_id",
    "유형",
    "상태",
    "신뢰 정책",
    "Scopes",
    "수정 일시",
  ])
    expect(screen.getByRole("columnheader", {name: label})).toBeVisible();
  await userEvent.click(screen.getByRole("button", {name: "다음"}));
  expect(replace).toHaveBeenCalledWith("/companies/ACME/oauth-clients?page=1", {
    scroll: false,
  });
});

it("denies cross-tenant and USER access before any list call", () => {
  const list = vi.spyOn(oauthClientApi, "list");
  const view = render(<OAuthClientTable companyCode="OTHER"/>);
  expect(screen.getByRole("alert")).toHaveTextContent("권한");
  state.roles = ["USER"];
  view.rerender(<OAuthClientTable companyCode="ACME"/>);
  expect(
      screen.queryByRole("link", {name: "OAuth client 생성"}),
  ).not.toBeInTheDocument();
  expect(list).not.toHaveBeenCalled();
});

it("allows system admin selected company and recovers failed load to empty state", async () => {
  state.roles = ["SYSTEM_ADMIN"];
  const list = vi
      .spyOn(oauthClientApi, "list")
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValue({
        content: [],
        page: 0,
        size: 20,
        totalElements: 0,
        totalPages: 0,
      });
  render(<OAuthClientTable companyCode="OTHER"/>);
  await userEvent.click(
      await screen.findByRole("button", {name: "다시 시도"}),
  );
  expect(
      await screen.findByText("등록된 OAuth client가 없습니다."),
  ).toBeVisible();
  expect(list).toHaveBeenLastCalledWith(
      "OTHER",
      {page: 0, size: 20},
      expect.any(AbortSignal),
  );
});

it("canonicalizes invalid pagination before fetching", async () => {
  state.query = "page=-1&size=200";
  const list = vi.spyOn(oauthClientApi, "list");
  render(<OAuthClientTable companyCode="ACME"/>);
  await waitFor(() =>
      expect(replace).toHaveBeenCalledWith("/companies/ACME/oauth-clients", {
        scroll: false,
      }),
  );
  expect(list).not.toHaveBeenCalled();
});
