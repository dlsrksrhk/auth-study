import { apiClient, type TokenResponse } from "@/lib/api/client";

export type Role = "SYSTEM_ADMIN" | "COMPANY_ADMIN" | "USER";

export type { TokenResponse } from "@/lib/api/client";

export type Actor = {
  accountId: number;
  email: string;
  roles: Role[];
  userCode: string | null;
  userName: string | null;
  companyCode: string | null;
};

export const authApi = {
  login: (email: string, password: string) =>
    apiClient.request<TokenResponse>(
      "/api/v1/auth/login",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ email, password }),
      },
      { authenticate: false, refreshOnUnauthorized: false },
    ),
  refresh: () => apiClient.refreshAccessToken(),
  me: (accessToken?: string) =>
    accessToken
      ? apiClient.requestWithAccessToken<Actor>("/api/v1/auth/me", accessToken)
      : apiClient.request<Actor>("/api/v1/auth/me"),
  logout: (accessToken: string | null) => apiClient.logout(accessToken),
  changePassword: (currentPassword: string, newPassword: string) =>
    apiClient.request<void>(
      "/api/v1/auth/password",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ currentPassword, newPassword }),
      },
      { refreshOnUnauthorized: false, allowPasswordChangeToken: true },
    ),
};
