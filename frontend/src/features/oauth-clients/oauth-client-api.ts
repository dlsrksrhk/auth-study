import type { PageResponse } from "@/features/companies/company-api";
import { apiClient } from "@/lib/api/client";

export type OAuthScope =
  | "openid"
  | "profile"
  | "email"
  | "hr.company"
  | "hr.organization"
  | "hr.roles";

export type OAuthClientStatus = "ACTIVE" | "INACTIVE";
export type OAuthClientTrust = "CONSENT_REQUIRED" | "TRUSTED_FIRST_PARTY";

export type OAuthClientInput = {
  displayName: string;
  publicClient: boolean;
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  scopes: OAuthScope[];
  trust: OAuthClientTrust;
};

export type OAuthClientUpdate = Omit<OAuthClientInput, "publicClient"> & {
  version: number;
};

export type OAuthClientSummary = {
  companyCode: string;
  clientId: string;
  displayName: string;
  status: OAuthClientStatus;
  trust: OAuthClientTrust;
  publicClient: boolean;
  redirectUris: string[];
  postLogoutRedirectUris: string[];
  scopes: OAuthScope[];
  activeSecretHint: string | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type OAuthClientDetail = OAuthClientSummary;

export type OneTimeSecretResult = {
  client: OAuthClientDetail;
  oneTimeSecret?: string;
};

export type ConsentSummary = {
  subject: string;
  approvedScopes: OAuthScope[];
  grantedAt: string;
  updatedAt: string;
};

export type ProtocolEventType =
  | "AUTHORIZATION_REQUEST_VALIDATED"
  | "LOGIN_REQUIRED"
  | "LOGIN_SUCCEEDED"
  | "LOGIN_FAILED"
  | "PASSWORD_CHANGE_REQUIRED"
  | "PASSWORD_CHANGED"
  | "CONSENT_GRANTED"
  | "CONSENT_DENIED"
  | "AUTHORIZATION_CODE_ISSUED"
  | "AUTHORIZATION_CODE_EXCHANGED"
  | "AUTHORIZATION_CODE_REPLAY_REJECTED"
  | "REFRESH_ROTATED"
  | "REFRESH_REUSE_DETECTED"
  | "AUTHORIZATION_REVOKED"
  | "USERINFO_SUCCEEDED"
  | "USERINFO_DENIED"
  | "LOGOUT_COMPLETED";

export type ProtocolEventOutcome = "SUCCESS" | "FAILURE" | "DENIED";

export type ProtocolEvent = {
  id: number;
  occurredAt: string;
  correlationId: string;
  eventType: ProtocolEventType;
  outcome: ProtocolEventOutcome;
  clientId: string | null;
  subject: string | null;
  authorizationId: string | null;
  errorCode: string | null;
  metadata: Record<string, unknown>;
};

export type CursorResponse<T> = {
  content: T[];
  nextCursor: string | null;
  hasNext: boolean;
};

export type PageParams = { page: number; size: number };
export type ProtocolEventParams = {
  size: number;
  cursor?: string;
  type?: ProtocolEventType;
  outcome?: ProtocolEventOutcome;
};

const jsonHeaders = { "Content-Type": "application/json" } as const;
const segment = (value: string) => encodeURIComponent(value);
const collectionPath = (companyCode: string) =>
  `/api/v1/admin/companies/${segment(companyCode)}/oauth-clients`;
const clientPath = (companyCode: string, clientId: string) =>
  `${collectionPath(companyCode)}/${segment(clientId)}`;
const pageQuery = ({ page, size }: PageParams) =>
  new URLSearchParams({ page: String(page), size: String(size) }).toString();

export const oauthClientApi = {
  list(companyCode: string, params: PageParams, signal?: AbortSignal) {
    return apiClient.request<PageResponse<OAuthClientSummary>>(
      `${collectionPath(companyCode)}?${pageQuery(params)}`,
      { signal },
    );
  },

  create(companyCode: string, input: OAuthClientInput) {
    return apiClient.request<OneTimeSecretResult>(collectionPath(companyCode), {
      method: "POST",
      headers: jsonHeaders,
      body: JSON.stringify(input),
    });
  },

  get(companyCode: string, clientId: string, signal?: AbortSignal) {
    return apiClient.request<OAuthClientDetail>(clientPath(companyCode, clientId), { signal });
  },

  update(companyCode: string, clientId: string, input: OAuthClientUpdate) {
    return apiClient.request<OAuthClientDetail>(clientPath(companyCode, clientId), {
      method: "PUT",
      headers: jsonHeaders,
      body: JSON.stringify(input),
    });
  },

  rotateSecret(companyCode: string, clientId: string) {
    return apiClient.request<OneTimeSecretResult>(`${clientPath(companyCode, clientId)}/rotate-secret`, {
      method: "POST",
    });
  },

  revokeSecret(companyCode: string, clientId: string) {
    return apiClient.request<OAuthClientDetail>(`${clientPath(companyCode, clientId)}/revoke-secret`, {
      method: "POST",
    });
  },

  enable(companyCode: string, clientId: string, version: number) {
    return statusChange(companyCode, clientId, "enable", version);
  },

  disable(companyCode: string, clientId: string, version: number) {
    return statusChange(companyCode, clientId, "disable", version);
  },

  listConsents(companyCode: string, clientId: string, params: PageParams, signal?: AbortSignal) {
    return apiClient.request<PageResponse<ConsentSummary>>(
      `${clientPath(companyCode, clientId)}/consents?${pageQuery(params)}`,
      { signal },
    );
  },

  revokeConsent(companyCode: string, clientId: string, subject: string) {
    return apiClient.request<void>(
      `${clientPath(companyCode, clientId)}/consents/${segment(subject)}`,
      { method: "DELETE" },
    );
  },

  revokeAuthorizations(companyCode: string, clientId: string) {
    return apiClient.request<void>(`${clientPath(companyCode, clientId)}/revoke-authorizations`, {
      method: "POST",
    });
  },

  listProtocolEvents(
    companyCode: string,
    clientId: string,
    params: ProtocolEventParams,
    signal?: AbortSignal,
  ) {
    const query = new URLSearchParams({ size: String(params.size) });
    if (params.cursor) query.set("cursor", params.cursor);
    if (params.type) query.set("type", params.type);
    if (params.outcome) query.set("outcome", params.outcome);
    return apiClient.request<CursorResponse<ProtocolEvent>>(
      `${clientPath(companyCode, clientId)}/protocol-events?${query}`,
      { signal },
    );
  },
};

function statusChange(companyCode: string, clientId: string, action: "enable" | "disable", version: number) {
  return apiClient.request<void>(`${clientPath(companyCode, clientId)}/${action}`, {
    method: "POST",
    headers: jsonHeaders,
    body: JSON.stringify({ version }),
  });
}
