import { act, render, screen, within, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";
import { oauthClientApi } from "./oauth-client-api";
import { OAuthConsentList } from "./oauth-consent-list";

const { auth } = vi.hoisted(() => ({
  auth: {
    status: "authenticated",
    actor: { accountId: 1, companyCode: "ACME", roles: ["COMPANY_ADMIN"] },
  },
}));
vi.mock("@/features/auth/auth-provider", () => ({ useAuth: () => auth }));
const subject = "12345678-abcd-4321-9876-abcdef123456";
const page = {
  content: [
    {
      subject,
      approvedScopes: ["profile" as const],
      grantedAt: "2026-08-21T00:00:00Z",
      updatedAt: "2026-08-22T00:00:00Z",
    },
  ],
  page: 0,
  size: 20,
  totalElements: 1,
  totalPages: 1,
};
beforeEach(() => {
  vi.restoreAllMocks();
  auth.status = "authenticated";
  auth.actor = { accountId: 1, companyCode: "ACME", roles: ["COMPANY_ADMIN"] };
  vi.spyOn(oauthClientApi, "listConsents").mockResolvedValue(page);
  vi.spyOn(oauthClientApi, "revokeConsent").mockResolvedValue();
  vi.spyOn(oauthClientApi, "revokeAuthorizations").mockResolvedValue();
});
it("shows abbreviated subject and catalog copy; full subject is only copied", async () => {
  const user = userEvent.setup();
  const copy = vi.spyOn(navigator.clipboard, "writeText").mockResolvedValue();
  const { container } = render(
    <OAuthConsentList companyCode="ACME" clientId="portal" />,
  );
  expect(await screen.findByText("12345678…3456")).toBeVisible();
  expect(screen.getByText(/profile · 이름/)).toBeVisible();
  expect(container.innerHTML).not.toContain(subject);
  await user.click(screen.getByRole("button", { name: "subject 복사" }));
  expect(copy).toHaveBeenCalledWith(subject);
  expect(container.innerHTML).not.toContain(subject);
});
it("confirms subject consent removal plus grants and refresh revocation then reloads", async () => {
  render(<OAuthConsentList companyCode="ACME" clientId="portal" />);
  await userEvent.click(
    await screen.findByRole("button", { name: "동의 폐기" }),
  );
  expect(screen.getByRole("dialog")).toHaveTextContent(
    /해당 사용자의.*해당 client.*로그인 유지 권한.*refresh.*폐기/,
  );
  expect(oauthClientApi.revokeConsent).not.toHaveBeenCalled();
  await userEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
  );
  await waitFor(() =>
    expect(oauthClientApi.listConsents).toHaveBeenCalledTimes(2),
  );
  expect(oauthClientApi.revokeConsent).toHaveBeenCalledWith(
    "ACME",
    "portal",
    subject,
  );
});
it("separately confirms all client authorizations while preserving consent", async () => {
  render(<OAuthConsentList companyCode="ACME" clientId="portal" />);
  await screen.findByText("12345678…3456");
  await userEvent.click(
    screen.getByRole("button", { name: "전체 로그인 유지 권한 폐기" }),
  );
  expect(screen.getByRole("dialog")).toHaveTextContent(
    /모든 사용자.*refresh.*폐기.*동의.*유지/,
  );
  await userEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
  );
  await waitFor(() =>
    expect(oauthClientApi.revokeAuthorizations).toHaveBeenCalledWith(
      "ACME",
      "portal",
    ),
  );
  expect(oauthClientApi.revokeConsent).not.toHaveBeenCalled();
});
it.each(["OTHER", "ACME"])(
  "blocks other tenant or non-admin: %s",
  async (company) => {
    if (company === "ACME") auth.actor.roles = ["EMPLOYEE"];
    render(<OAuthConsentList companyCode={company} clientId="portal" />);
    expect(screen.getByRole("alert")).toBeVisible();
    expect(oauthClientApi.listConsents).not.toHaveBeenCalled();
  },
);
it("discards a late consent response after actor switch", async () => {
  let resolve!: (value: typeof page) => void;
  vi.mocked(oauthClientApi.listConsents).mockReturnValueOnce(
    new Promise((done) => {
      resolve = done;
    }),
  );
  const view = render(
    <OAuthConsentList companyCode="ACME" clientId="portal" />,
  );
  await waitFor(() => expect(oauthClientApi.listConsents).toHaveBeenCalled());
  auth.actor.accountId = 2;
  vi.mocked(oauthClientApi.listConsents).mockResolvedValue({
    ...page,
    content: [],
  });
  view.rerender(<OAuthConsentList companyCode="ACME" clientId="portal" />);
  await screen.findByText("승인된 동의가 없습니다.");
  await act(async () => resolve(page));
  expect(screen.queryByText("12345678…3456")).not.toBeInTheDocument();
});
it("supports consent pages and recovers after deleting the last row on a later page", async () => {
  vi.mocked(oauthClientApi.listConsents).mockResolvedValue({
    ...page,
    totalPages: 2,
    totalElements: 21,
  });
  render(<OAuthConsentList companyCode="ACME" clientId="portal" />);
  await screen.findByText("12345678…3456");
  await userEvent.click(screen.getByRole("button", { name: "다음 동의" }));
  await waitFor(() =>
    expect(oauthClientApi.listConsents).toHaveBeenLastCalledWith(
      "ACME",
      "portal",
      { page: 1, size: 20 },
      expect.any(AbortSignal),
    ),
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "동의 폐기" }),
  );
  vi.mocked(oauthClientApi.listConsents)
    .mockResolvedValueOnce({ ...page, content: [], page: 1 })
    .mockResolvedValue(page);
  await userEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
  );
  await waitFor(() =>
    expect(oauthClientApi.listConsents).toHaveBeenLastCalledWith(
      "ACME",
      "portal",
      { page: 0, size: 20 },
      expect.any(AbortSignal),
    ),
  );
  expect(await screen.findByText("12345678…3456")).toBeVisible();
});
it("keeps failed revocation retryable and never displays raw error details", async () => {
  vi.mocked(oauthClientApi.revokeConsent).mockRejectedValue(
    new Error("sensitive-payload"),
  );
  render(<OAuthConsentList companyCode="ACME" clientId="portal" />);
  await userEvent.click(
    await screen.findByRole("button", { name: "동의 폐기" }),
  );
  await userEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "취소" }),
  );
  expect(oauthClientApi.revokeConsent).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole("button", { name: "동의 폐기" }));
  await userEvent.click(
    within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "폐기하지 못했습니다",
  );
  expect(document.body).not.toHaveTextContent("sensitive-payload");
  expect(screen.getByRole("button", { name: "동의 폐기" })).toBeEnabled();
});
it("blocks repeated confirmation and ignores pending mutation completion after logout", async () => {
  let resolve!: () => void;
  vi.mocked(oauthClientApi.revokeConsent).mockReturnValue(
    new Promise((done) => {
      resolve = done;
    }),
  );
  const view = render(
    <OAuthConsentList companyCode="ACME" clientId="portal" />,
  );
  await userEvent.click(
    await screen.findByRole("button", { name: "동의 폐기" }),
  );
  await userEvent.dblClick(
    within(screen.getByRole("dialog")).getByRole("button", { name: "확인" }),
  );
  expect(oauthClientApi.revokeConsent).toHaveBeenCalledTimes(1);
  expect(screen.getByRole("button", { name: "처리 중" })).toBeDisabled();
  auth.status = "unauthenticated";
  view.rerender(<OAuthConsentList companyCode="ACME" clientId="portal" />);
  await act(async () => resolve());
  expect(oauthClientApi.listConsents).toHaveBeenCalledTimes(1);
  expect(
    screen.queryByText("폐기 처리가 완료되었습니다."),
  ).not.toBeInTheDocument();
});
