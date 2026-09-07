import {render, screen, waitFor} from "@testing-library/react";
import {beforeEach, expect, it, vi} from "vitest";
import AdminLayout from "@/app/(admin)/layout";
import {OAuthClientTable} from "./oauth-client-table";
import {oauthClientApi} from "./oauth-client-api";
import {OAuthClientForm} from "./oauth-client-form";

const {auth, route, replace} = vi.hoisted(() => ({
  auth: {status: "authenticated", actor: {roles: ["USER"], companyCode: "ACME", accountId: 1}},
  route: {pathname: "/companies/ACME/oauth-clients"},
  replace: vi.fn(),
}));
vi.mock("next/navigation", () => ({
  usePathname: () => route.pathname,
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({replace})
}));
vi.mock("@/features/auth/auth-provider", () => ({useAuth: () => auth}));
vi.mock("@/components/layout/admin-header", () => ({AdminHeader: () => null}));
beforeEach(() => {
  vi.restoreAllMocks();
  replace.mockReset();
  auth.actor.roles = ["USER"];
  route.pathname = "/companies/ACME/oauth-clients";
});

it("blocks direct OAuth URL before rendering or requesting protected content", async () => {
  const list = vi.spyOn(oauthClientApi, "list");
  render(<AdminLayout><OAuthClientTable companyCode="ACME"/></AdminLayout>);
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/account"));
  expect(list).not.toHaveBeenCalled();
  expect(screen.queryByRole("link", {name: "OAuth client 생성"})).not.toBeInTheDocument();
});

it("blocks another tenant before fetching client data", async () => {
  auth.actor.roles = ["COMPANY_ADMIN"];
  route.pathname = "/companies/OTHER/oauth-clients";
  const list = vi.spyOn(oauthClientApi, "list");
  render(<AdminLayout><OAuthClientTable companyCode="OTHER"/></AdminLayout>);
  await waitFor(() => expect(replace).toHaveBeenCalledWith("/companies/ACME/oauth-clients"));
  expect(list).not.toHaveBeenCalled();
});

it("provides company selection guidance for system administration", () => {
  auth.actor.roles = ["SYSTEM_ADMIN"];
  route.pathname = "/";
  render(<AdminLayout><p>대시보드</p></AdminLayout>);
  expect(screen.getByText("인증/인가 설정을 관리하려면 먼저 회사를 선택해 주세요.")).toBeVisible();
});

it("explicitly associates every OAuth form label with its control", () => {
  const {container} = render(<OAuthClientForm canSetTrust onSubmit={vi.fn()}/>);
  for (const label of container.querySelectorAll("label")) {
    expect(label.htmlFor).not.toBe("");
    expect(document.getElementById(label.htmlFor)).not.toBeNull();
  }
});
