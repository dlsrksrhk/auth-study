import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { AuthProvider } from "@/features/auth/auth-provider";
import { authSession } from "@/features/auth/auth-session";
import { server } from "@/test/setup";
import LoginPage from "./page";

const push = vi.fn();
const origin = "http://localhost:3000";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push }),
}));

describe("LoginPage", () => {
  beforeEach(() => {
    push.mockReset();
    authSession.clear();
    localStorage.clear();
    sessionStorage.clear();
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, () => new HttpResponse(null, { status: 401 })),
    );
  });

  it("routes a forced-password login without persisting the access token", async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${origin}/api/v1/auth/login`, async ({ request }) => {
        expect(await request.json()).toEqual({
          email: "new.user@acme.example",
          password: "Temporary1234!",
        });
        return HttpResponse.json({
          accessToken: "password-only-token",
          accessTokenExpiresAt: "2026-08-20T01:00:00Z",
          mustChangePassword: true,
        });
      }),
    );
    render(
      <AuthProvider>
        <LoginPage />
      </AuthProvider>,
    );

    await user.type(screen.getByLabelText("이메일"), "new.user@acme.example");
    await user.type(screen.getByLabelText("비밀번호"), "Temporary1234!");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(push).toHaveBeenCalledWith("/change-password");
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it("maps field errors, exposes the trace id, and moves focus to the error summary", async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${origin}/api/v1/auth/login`, () =>
        HttpResponse.json(
          {
            type: "https://auth-study.local/problems/validation-failed",
            title: "Validation failed",
            status: 400,
            detail: "Validation failed.",
            code: "VALIDATION_FAILED",
            traceId: "trace-login",
            fieldErrors: [{ field: "email", message: "이메일을 확인해 주세요." }],
          },
          { status: 400 },
        ),
      ),
    );
    render(
      <AuthProvider>
        <LoginPage />
      </AuthProvider>,
    );

    await user.type(screen.getByLabelText("이메일"), "invalid");
    await user.type(screen.getByLabelText("비밀번호"), "wrong-password");
    await user.click(screen.getByRole("button", { name: "로그인" }));

    expect(await screen.findByText("이메일을 확인해 주세요.")).toBeVisible();
    const summary = screen.getByRole("alert");
    expect(summary).toHaveTextContent("추적 ID: trace-login");
    expect(summary).toHaveFocus();
  });
});
