import { HttpResponse, delay, http } from "msw";
import { beforeEach, describe, expect, it } from "vitest";

import { createApiClient } from "./client";
import { createMemoryAuthSession } from "@/features/auth/auth-session";
import {
  AUTH_OPERATION_LOCK_NAME,
  createAuthOperationLock,
  type WebLockManagerLike,
} from "@/features/auth/auth-operation-lock";
import { server } from "@/test/setup";

const origin = "http://localhost:3000";

describe("apiClient", () => {
  let refreshCalls: number;

  beforeEach(() => {
    refreshCalls = 0;
  });

  it("single-flights concurrent 401 responses and retries each request exactly once", async () => {
    const session = createMemoryAuthSession();
    session.set("expired-access", "authenticated");
    let protectedCalls = 0;
    server.use(
      http.get(`${origin}/api/protected`, ({ request }) => {
        protectedCalls += 1;
        if (request.headers.get("Authorization") === "Bearer fresh-access") {
          return HttpResponse.json({ ok: true });
        }
        return new HttpResponse(null, { status: 401 });
      }),
      http.post(`${origin}/api/v1/auth/refresh`, async () => {
        refreshCalls += 1;
        await delay(20);
        return HttpResponse.json({
          accessToken: "fresh-access",
          accessTokenExpiresAt: "2026-08-20T01:00:00Z",
          mustChangePassword: false,
        });
      }),
    );
    const client = createApiClient({ session });

    const responses = await Promise.all([
      client.request<{ ok: boolean }>("/api/protected"),
      client.request<{ ok: boolean }>("/api/protected"),
    ]);

    expect(responses).toEqual([{ ok: true }, { ok: true }]);
    expect(refreshCalls).toBe(1);
    expect(protectedCalls).toBe(4);
  });

  it("replays body and headers safely when retrying", async () => {
    const session = createMemoryAuthSession();
    session.set("expired-access", "authenticated");
    const bodies: string[] = [];
    const customHeaders: string[] = [];
    server.use(
      http.post(`${origin}/api/protected`, async ({ request }) => {
        bodies.push(await request.text());
        customHeaders.push(request.headers.get("X-Custom") ?? "");
        return request.headers.get("Authorization") === "Bearer fresh-access"
          ? HttpResponse.json({ ok: true })
          : new HttpResponse(null, { status: 401 });
      }),
      http.post(`${origin}/api/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json({
          accessToken: "fresh-access",
          accessTokenExpiresAt: "2026-08-20T01:00:00Z",
          mustChangePassword: false,
        });
      }),
    );
    const client = createApiClient({ session });

    await client.request("/api/protected", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Custom": "preserved" },
      body: JSON.stringify({ hello: "world" }),
    });

    expect(bodies).toEqual(['{"hello":"world"}', '{"hello":"world"}']);
    expect(customHeaders).toEqual(["preserved", "preserved"]);
    expect(refreshCalls).toBe(1);
  });

  it("does not refresh an aborted request", async () => {
    const session = createMemoryAuthSession();
    session.set("access", "authenticated");
    server.use(
      http.get(`${origin}/api/slow`, async () => {
        await delay(100);
        return new HttpResponse(null, { status: 401 });
      }),
      http.post(`${origin}/api/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const client = createApiClient({ session });
    const controller = new AbortController();
    const promise = client.request("/api/slow", { signal: controller.signal });
    controller.abort();

    await expect(promise).rejects.toMatchObject({ name: "AbortError" });
    expect(refreshCalls).toBe(0);
  });

  it("becomes anonymous after a retried 401 without entering a refresh loop", async () => {
    const session = createMemoryAuthSession();
    session.set("expired-access", "authenticated");
    let protectedCalls = 0;
    server.use(
      http.get(`${origin}/api/protected`, () => {
        protectedCalls += 1;
        return new HttpResponse(null, { status: 401 });
      }),
      http.post(`${origin}/api/v1/auth/refresh`, () => {
        refreshCalls += 1;
        return HttpResponse.json({
          accessToken: "still-invalid",
          accessTokenExpiresAt: "2026-08-20T01:00:00Z",
          mustChangePassword: false,
        });
      }),
    );
    const client = createApiClient({ session });

    await expect(client.request("/api/protected")).rejects.toMatchObject({ status: 401 });
    expect(session.get()).toMatchObject({ accessToken: null, mode: "anonymous" });
    expect(refreshCalls).toBe(1);
    expect(protectedCalls).toBe(2);
  });

  it("blocks password-change-only tokens outside the password endpoint", async () => {
    const session = createMemoryAuthSession();
    session.set("forced-token", "passwordChangeRequired");
    const client = createApiClient({ session });

    await expect(client.request("/api/v1/auth/me")).rejects.toMatchObject({
      status: 403,
      code: "PASSWORD_CHANGE_REQUIRED",
    });
    expect(refreshCalls).toBe(0);
  });

  it("does not let a retried 401 clear a newer login session", async () => {
    const session = createMemoryAuthSession();
    session.set("expired-access", "authenticated");
    const retryStarted = deferred<void>();
    const finishRetry = deferred<void>();
    server.use(
      http.get(`${origin}/api/protected`, async ({ request }) => {
        if (request.headers.get("Authorization") === "Bearer fresh-access") {
          retryStarted.resolve();
          await finishRetry.promise;
        }
        return new HttpResponse(null, { status: 401 });
      }),
      http.post(`${origin}/api/v1/auth/refresh`, () =>
        HttpResponse.json({
          accessToken: "fresh-access",
          accessTokenExpiresAt: "2026-08-20T01:00:00Z",
          mustChangePassword: false,
        }),
      ),
    );
    const client = createApiClient({ session });
    const request = client.request("/api/protected");
    await retryStarted.promise;

    session.set("new-login-token", "authenticated");
    finishRetry.resolve();

    await expect(request).rejects.toMatchObject({ status: 401 });
    expect(session.get()).toMatchObject({
      accessToken: "new-login-token",
      mode: "authenticated",
    });
  });

  it("does not let a failed refresh clear a newer login session", async () => {
    const session = createMemoryAuthSession();
    session.set("expired-access", "authenticated");
    const refreshStarted = deferred<void>();
    const finishRefresh = deferred<void>();
    server.use(
      http.get(`${origin}/api/protected`, () => new HttpResponse(null, { status: 401 })),
      http.post(`${origin}/api/v1/auth/refresh`, async () => {
        refreshStarted.resolve();
        await finishRefresh.promise;
        return new HttpResponse(null, { status: 401 });
      }),
    );
    const client = createApiClient({ session });
    const request = client.request("/api/protected");
    await refreshStarted.promise;

    session.set("new-login-token", "authenticated");
    finishRefresh.resolve();

    await expect(request).rejects.toMatchObject({ status: 401 });
    expect(session.get()).toMatchObject({
      accessToken: "new-login-token",
      mode: "authenticated",
    });
  });

  it("serializes refresh rotation across independent client contexts with the named Web Lock", async () => {
    const webLocks = new FakeWebLocks();
    const sessionA = createMemoryAuthSession();
    const sessionB = createMemoryAuthSession();
    sessionA.set("expired-a", "authenticated");
    sessionB.set("expired-b", "authenticated");
    let refreshNumber = 0;
    let activeRefreshes = 0;
    let maxActiveRefreshes = 0;
    server.use(
      http.get(`${origin}/api/protected`, ({ request }) =>
        request.headers.get("Authorization")?.startsWith("Bearer fresh-")
          ? HttpResponse.json({ ok: true })
          : new HttpResponse(null, { status: 401 }),
      ),
      http.post(`${origin}/api/v1/auth/refresh`, async () => {
        activeRefreshes += 1;
        maxActiveRefreshes = Math.max(maxActiveRefreshes, activeRefreshes);
        await new Promise((resolve) => setTimeout(resolve, 10));
        activeRefreshes -= 1;
        refreshNumber += 1;
        return HttpResponse.json({
          accessToken: `fresh-${refreshNumber}`,
          accessTokenExpiresAt: "2026-08-20T01:00:00Z",
          mustChangePassword: false,
        });
      }),
    );
    const clientA = createApiClient({ session: sessionA, authLock: createAuthOperationLock(webLocks) });
    const clientB = createApiClient({ session: sessionB, authLock: createAuthOperationLock(webLocks) });

    await Promise.all([
      clientA.request("/api/protected"),
      clientB.request("/api/protected"),
    ]);

    expect(refreshNumber).toBe(2);
    expect(maxActiveRefreshes).toBe(1);
    expect(webLocks.requestedNames).toEqual([AUTH_OPERATION_LOCK_NAME, AUTH_OPERATION_LOCK_NAME]);
  });
});

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, resolve, reject };
}

class FakeWebLocks implements WebLockManagerLike {
  readonly requestedNames: string[] = [];
  private tails = new Map<string, Promise<void>>();

  async request<T>(name: string, callback: () => Promise<T>): Promise<T> {
    this.requestedNames.push(name);
    const previous = this.tails.get(name) ?? Promise.resolve();
    let release!: () => void;
    const current = new Promise<void>((resolve) => {
      release = resolve;
    });
    this.tails.set(name, previous.catch(() => undefined).then(() => current));
    await previous.catch(() => undefined);
    try {
      return await callback();
    } finally {
      release();
    }
  }
}
