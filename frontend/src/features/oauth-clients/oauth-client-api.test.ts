import { beforeEach, describe, expect, expectTypeOf, it, vi } from "vitest";

import { ApiProblemError } from "@/lib/api/problem";
import {
  oauthClientApi,
  type OAuthClientDetail,
  type OneTimeSecretResult,
} from "./oauth-client-api";
import { oauthScopeCatalog } from "./oauth-scope-catalog";

const detail = {
  companyCode: "ACME",
  clientId: "client/id+value",
  displayName: "Payroll",
  status: "ACTIVE",
  trust: "CONSENT_REQUIRED",
  publicClient: false,
  redirectUris: ["https://payroll.example/callback"],
  postLogoutRedirectUris: [],
  scopes: ["openid"],
  activeSecretHint: "…abcd",
  version: 3,
  createdAt: "2026-08-21T00:00:00Z",
  updatedAt: "2026-08-21T00:00:00Z",
} as const;

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("oauthClientApi", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(detail)));
  });

  it("encodes every dynamic path segment", async () => {
    await oauthClientApi.get("ACME", "client/id+value");

    expect(fetch).toHaveBeenCalledWith(
      expect.stringContaining("/companies/ACME/oauth-clients/client%2Fid%2Bvalue"),
      expect.anything(),
    );
  });

  it("keeps a one-time secret outside ordinary client details", () => {
    expectTypeOf<OAuthClientDetail>().not.toHaveProperty("oneTimeSecret");
    expectTypeOf<OneTimeSecretResult>().toHaveProperty("oneTimeSecret").toEqualTypeOf<string | undefined>();
    expectTypeOf<OneTimeSecretResult>().toHaveProperty("client").toEqualTypeOf<OAuthClientDetail>();
  });

  it("matches client mutation methods and their response boundaries", async () => {
    const input = {
      displayName: "Payroll",
      publicClient: false,
      redirectUris: ["https://payroll.example/callback"],
      postLogoutRedirectUris: [],
      scopes: ["openid" as const],
      trust: "CONSENT_REQUIRED" as const,
    };
    const oneTime = { client: detail, oneTimeSecret: "shown-once" };
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(oneTime, 201))
      .mockResolvedValueOnce(jsonResponse(detail))
      .mockResolvedValueOnce(jsonResponse(oneTime))
      .mockResolvedValueOnce(jsonResponse(detail))
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    await expect(oauthClientApi.create("ACME", input)).resolves.toEqual(oneTime);
    await expect(oauthClientApi.update("ACME", "client/id+value", { ...input, version: 3 })).resolves.toEqual(detail);
    await expect(oauthClientApi.rotateSecret("ACME", "client/id+value")).resolves.toEqual(oneTime);
    await expect(oauthClientApi.revokeSecret("ACME", "client/id+value")).resolves.toEqual(detail);
    await oauthClientApi.disable("ACME", "client/id+value", 3);
    await oauthClientApi.enable("ACME", "client/id+value", 4);

    expect(vi.mocked(fetch).mock.calls.map(([url, init]) => [new URL(String(url)).pathname, init?.method, init?.body])).toEqual([
      ["/api/v1/admin/companies/ACME/oauth-clients", "POST", JSON.stringify(input)],
      ["/api/v1/admin/companies/ACME/oauth-clients/client%2Fid%2Bvalue", "PUT", JSON.stringify({ ...input, version: 3 })],
      ["/api/v1/admin/companies/ACME/oauth-clients/client%2Fid%2Bvalue/rotate-secret", "POST", undefined],
      ["/api/v1/admin/companies/ACME/oauth-clients/client%2Fid%2Bvalue/revoke-secret", "POST", undefined],
      ["/api/v1/admin/companies/ACME/oauth-clients/client%2Fid%2Bvalue/disable", "POST", JSON.stringify({ version: 3 })],
      ["/api/v1/admin/companies/ACME/oauth-clients/client%2Fid%2Bvalue/enable", "POST", JSON.stringify({ version: 4 })],
    ]);
  });

  it("uses page pagination for clients and consents, but cursor pagination for events", async () => {
    const page = { content: [], page: 1, size: 20, totalElements: 0, totalPages: 0 };
    const cursor = { content: [], nextCursor: "opaque+cursor/=", hasNext: true };
    vi.mocked(fetch)
      .mockResolvedValueOnce(jsonResponse(page))
      .mockResolvedValueOnce(jsonResponse(page))
      .mockResolvedValueOnce(jsonResponse(cursor));

    await expect(oauthClientApi.list("A/B", { page: 1, size: 20 })).resolves.toEqual(page);
    await expect(oauthClientApi.listConsents("A/B", "client/id", { page: 1, size: 20 })).resolves.toEqual(page);
    await expect(oauthClientApi.listProtocolEvents("A/B", "client/id", {
      cursor: "opaque+cursor/=",
      size: 50,
      type: "AUTHORIZATION_CODE_EXCHANGED",
      outcome: "SUCCESS",
    })).resolves.toEqual(cursor);

    expect(vi.mocked(fetch).mock.calls.map(([url]) => String(url))).toEqual([
      expect.stringContaining("/companies/A%2FB/oauth-clients?page=1&size=20"),
      expect.stringContaining("/companies/A%2FB/oauth-clients/client%2Fid/consents?page=1&size=20"),
      expect.stringContaining("/companies/A%2FB/oauth-clients/client%2Fid/protocol-events?size=50&cursor=opaque%2Bcursor%2F%3D&type=AUTHORIZATION_CODE_EXCHANGED&outcome=SUCCESS"),
    ]);
  });

  it("encodes consent subjects and calls both revocation endpoints", async () => {
    vi.mocked(fetch)
      .mockResolvedValueOnce(new Response(null, { status: 204 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));

    await oauthClientApi.revokeConsent("A/B", "client/id", "subject/value");
    await oauthClientApi.revokeAuthorizations("A/B", "client/id");

    expect(vi.mocked(fetch).mock.calls.map(([url, init]) => [new URL(String(url)).pathname, init?.method])).toEqual([
      ["/api/v1/admin/companies/A%2FB/oauth-clients/client%2Fid/consents/subject%2Fvalue", "DELETE"],
      ["/api/v1/admin/companies/A%2FB/oauth-clients/client%2Fid/revoke-authorizations", "POST"],
    ]);
  });

  it("propagates administrator Problem Details unchanged", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse({
      type: "https://auth-study.local/problems/optimistic-lock-conflict",
      title: "Conflict",
      status: 409,
      detail: "OAuth client version does not match.",
      instance: "/api/v1/admin/companies/ACME/oauth-clients/client",
      code: "OPTIMISTIC_LOCK_CONFLICT",
      traceId: "oauth-trace",
      fieldErrors: [{ field: "version", message: "최신 정보를 다시 불러오세요." }],
    }, 409));

    const error = await oauthClientApi.get("ACME", "client").catch((cause: unknown) => cause);
    expect(error).toBeInstanceOf(ApiProblemError);
    expect(error).toMatchObject({
      status: 409,
      instance: "/api/v1/admin/companies/ACME/oauth-clients/client",
      code: "OPTIMISTIC_LOCK_CONFLICT",
      traceId: "oauth-trace",
      fieldErrors: [{ field: "version", message: "최신 정보를 다시 불러오세요." }],
    });
  });
});

describe("oauthScopeCatalog", () => {
  it("uses the fixed privacy-preserving labels", () => {
    expect(oauthScopeCatalog).toEqual({
      openid: { label: "기본 식별자", description: "기본 식별자" },
      profile: { label: "이름", description: "이름" },
      email: { label: "로그인 이메일", description: "로그인 이메일" },
      "hr.company": { label: "회사 정보", description: "회사 정보" },
      "hr.organization": { label: "부서·직위 정보", description: "부서·직위 정보" },
      "hr.roles": { label: "HR 역할", description: "HR 역할" },
    });
  });
});
