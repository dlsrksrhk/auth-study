import { StrictMode } from "react";
import { act, render, screen } from "@testing-library/react";
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
      <button
        type="button"
        onClick={() => void auth.login("chosen@acme.example", "Password1234!").catch(() => undefined)}
      >
        선택 계정 로그인
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

  it("does not refresh after logout returns 401", async () => {
    const user = userEvent.setup();
    let refreshCalls = 0;
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json(token("access-logout"));
      }),
      http.get(`${origin}/api/v1/auth/me`, () => HttpResponse.json(actor("admin@auth-study.local"))),
      http.post(`${origin}/api/v1/auth/logout`, () => new HttpResponse(null, { status: 401 })),
    );

    render(<AuthProvider><Probe /></AuthProvider>);
    expect(await screen.findByText("admin@auth-study.local")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "로그아웃" }));
    expect(await screen.findByText("anonymous")).toBeInTheDocument();

    await waitForMicrotasks();
    expect(refreshCalls).toBe(1);
    expect(authSession.get()).toMatchObject({ accessToken: null, mode: "anonymous" });
  });

  it("keeps logout authoritative over an earlier pending restore", async () => {
    const user = userEvent.setup();
    const finishRefresh = deferred<void>();
    const refreshStarted = deferred<void>();
    let meCalls = 0;
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, async () => {
        refreshStarted.resolve();
        await finishRefresh.promise;
        return HttpResponse.json(token("late-refresh"));
      }),
      http.get(`${origin}/api/v1/auth/me`, () => {
        meCalls += 1;
        return HttpResponse.json(actor("restored@auth-study.local"));
      }),
      http.post(`${origin}/api/v1/auth/logout`, () => new HttpResponse(null, { status: 204 })),
    );

    render(<AuthProvider><Probe /></AuthProvider>);
    await refreshStarted.promise;
    await user.click(screen.getByRole("button", { name: "로그아웃" }));
    expect(await screen.findByText("anonymous")).toBeInTheDocument();

    await act(async () => finishRefresh.resolve());
    await waitForMicrotasks();
    expect(screen.getByText("anonymous")).toBeInTheDocument();
    expect(authSession.get()).toMatchObject({ accessToken: null, mode: "anonymous" });
    expect(meCalls).toBe(0);
  });

  it("keeps the interactive login when it finishes before bootstrap restore", async () => {
    const user = userEvent.setup();
    const finishRefresh = deferred<void>();
    const refreshStarted = deferred<void>();
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, async () => {
        refreshStarted.resolve();
        await finishRefresh.promise;
        return HttpResponse.json(token("restore-token"));
      }),
      http.post(`${origin}/api/v1/auth/login`, () => HttpResponse.json(token("chosen-token"))),
      http.get(`${origin}/api/v1/auth/me`, ({ request }) =>
        HttpResponse.json(
          request.headers.get("Authorization") === "Bearer chosen-token"
            ? actor("chosen@acme.example")
            : actor("restored@auth-study.local"),
        ),
      ),
    );

    render(<AuthProvider><Probe /></AuthProvider>);
    await refreshStarted.promise;
    await user.click(screen.getByRole("button", { name: "선택 계정 로그인" }));
    expect(await screen.findByText("chosen@acme.example")).toBeInTheDocument();

    await act(async () => finishRefresh.resolve());
    await waitForMicrotasks();
    expect(screen.getByText("chosen@acme.example")).toBeInTheDocument();
    expect(screen.queryByText("restored@auth-study.local")).toBeNull();
    expect(authSession.get()).toMatchObject({ accessToken: "chosen-token" });
  });

  it("keeps the interactive login when bootstrap restore finishes first", async () => {
    const user = userEvent.setup();
    const finishLogin = deferred<void>();
    const loginStarted = deferred<void>();
    const finishRefresh = deferred<void>();
    const refreshStarted = deferred<void>();
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, async () => {
        refreshStarted.resolve();
        await finishRefresh.promise;
        return HttpResponse.json(token("restore-token"));
      }),
      http.post(`${origin}/api/v1/auth/login`, async () => {
        loginStarted.resolve();
        await finishLogin.promise;
        return HttpResponse.json(token("chosen-token"));
      }),
      http.get(`${origin}/api/v1/auth/me`, ({ request }) =>
        HttpResponse.json(
          request.headers.get("Authorization") === "Bearer chosen-token"
            ? actor("chosen@acme.example")
            : actor("restored@auth-study.local"),
        ),
      ),
    );

    render(<AuthProvider><Probe /></AuthProvider>);
    await refreshStarted.promise;
    await user.click(screen.getByRole("button", { name: "선택 계정 로그인" }));
    await loginStarted.promise;
    await act(async () => finishRefresh.resolve());
    await waitForMicrotasks();
    await act(async () => finishLogin.resolve());

    expect(await screen.findByText("chosen@acme.example")).toBeInTheDocument();
    expect(screen.queryByText("restored@auth-study.local")).toBeNull();
    expect(authSession.get()).toMatchObject({ accessToken: "chosen-token" });
  });

  it("rolls back a login token when loading the actor fails", async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${origin}/api/v1/auth/refresh`, () => new HttpResponse(null, { status: 401 })),
      http.post(`${origin}/api/v1/auth/login`, () => HttpResponse.json(token("orphan-token"))),
      http.get(`${origin}/api/v1/auth/me`, () => new HttpResponse(null, { status: 500 })),
    );

    render(<AuthProvider><Probe /></AuthProvider>);
    expect(await screen.findByText("anonymous")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "선택 계정 로그인" }));
    await waitForMicrotasks();

    expect(screen.getByText("anonymous")).toBeInTheDocument();
    expect(authSession.get()).toMatchObject({ accessToken: null, mode: "anonymous" });
  });
});

function token(accessToken: string) {
  return {
    accessToken,
    accessTokenExpiresAt: "2026-08-20T01:00:00Z",
    mustChangePassword: false,
  };
}

function actor(email: string) {
  return {
    accountId: email.length,
    email,
    roles: ["SYSTEM_ADMIN"],
    userCode: null,
    userName: null,
    companyCode: null,
  };
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

async function waitForMicrotasks() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}
