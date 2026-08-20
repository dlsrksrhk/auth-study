import { HttpResponse, delay, http } from "msw";
import { beforeEach, describe, expect, it } from "vitest";

import { createApiClient } from "./client";
import { createMemoryAuthSession } from "@/features/auth/auth-session";
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
    expect(session.get()).toEqual({ accessToken: null, mode: "anonymous" });
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
});
