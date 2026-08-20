import { StrictMode } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HttpResponse, http } from "msw";
import { beforeEach, describe, expect, it } from "vitest";

import { server } from "@/test/setup";
import { AuthProvider, useAuth } from "./auth-provider";
import { authSession } from "./auth-session";

const origin = "http://localhost:3000";

function Probe() {
  const auth = useAuth();
  return (
    <div>
      <span>{auth.status}</span>
      <span>{auth.actor?.email}</span>
      <button type="button" onClick={() => void auth.logout()}>
        로그아웃
      </button>
    </div>
  );
}

describe("AuthProvider", () => {
  beforeEach(() => authSession.clear());

  it("restores authentication once and exposes the current actor", async () => {
    let refreshCalls = 0;
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json({
          accessToken: "access-1",
          accessTokenExpiresAt: "2026-08-20T01:00:00Z",
          mustChangePassword: false,
        });
      }),
      http.get(`${origin}/api/v1/auth/me`, ({ request }) => {
        expect(request.headers.get("Authorization")).toBe("Bearer access-1");
        return HttpResponse.json({
          accountId: 1,
          email: "admin@auth-study.local",
          roles: ["SYSTEM_ADMIN"],
          userCode: null,
          userName: null,
          companyCode: null,
        });
      }),
    );

    render(
      <StrictMode>
        <AuthProvider>
          <Probe />
        </AuthProvider>
      </StrictMode>,
    );

    expect(await screen.findByText("admin@auth-study.local")).toBeInTheDocument();
    expect(refreshCalls).toBe(1);
  });

  it("becomes anonymous when refresh has no valid cookie", async () => {
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, () =>
        HttpResponse.json(
          {
            type: "https://auth-study.local/problems/unauthenticated",
            title: "Unauthenticated",
            status: 401,
            detail: "Authentication failed.",
            code: "UNAUTHENTICATED",
            traceId: "trace-refresh",
            fieldErrors: [],
          },
          { status: 401 },
        ),
      ),
    );

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );

    expect(await screen.findByText("anonymous")).toBeInTheDocument();
  });

  it("clears local authentication even when server logout fails", async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, () =>
        HttpResponse.json({
          accessToken: "access-logout",
          accessTokenExpiresAt: "2026-08-20T01:00:00Z",
          mustChangePassword: false,
        }),
      ),
      http.get(`${origin}/api/v1/auth/me`, () =>
        HttpResponse.json({
          accountId: 1,
          email: "admin@auth-study.local",
          roles: ["SYSTEM_ADMIN"],
          userCode: null,
          userName: null,
          companyCode: null,
        }),
      ),
      http.post(`${origin}/api/v1/auth/logout`, () =>
        HttpResponse.json(
          {
            type: "about:blank",
            title: "Internal Server Error",
            status: 500,
            detail: "Temporary failure.",
            code: "INTERNAL_ERROR",
            traceId: "trace-logout",
            fieldErrors: [],
          },
          { status: 500 },
        ),
      ),
    );

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );
    expect(await screen.findByText("authenticated")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "로그아웃" }));
    expect(await screen.findByText("anonymous")).toBeInTheDocument();
  });
});
