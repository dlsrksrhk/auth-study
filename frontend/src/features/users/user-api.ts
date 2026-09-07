import type {PageResponse} from "@/features/companies/company-api";
import type {Role} from "@/features/auth/auth-api";
import {apiClient} from "@/lib/api/client";
import {resourceCode} from "@/lib/resource-code";

export type UserStatus = "PENDING" | "ACTIVE" | "LOCKED" | "RESIGNED";
export type User = {
  id: number;
  companyId: number;
  code: string;
  employeeNumber: string;
  name: string;
  loginEmail: string;
  roles: readonly Role[];
  phone: string;
  hiredAt: string;
  workplace: string;
  profileImageUrl: string;
  positionId: number;
  status: UserStatus;
  version: number;
  createdAt: string;
  updatedAt: string
};
export type UserInput = {
  code: string;
  employeeNumber: string;
  name: string;
  loginEmail: string;
  phone: string;
  hiredAt: string;
  workplace: string;
  profileImageUrl: string;
  positionCode: string
};
export type MembershipRole = "HEAD" | "DEPUTY_HEAD" | "MEMBER";
export type Membership = {
  id: number;
  companyId: number;
  userId: number;
  departmentId: number;
  role: MembershipRole;
  primary: boolean;
  startedAt: string;
  endedAt: string | null;
  version: number
};
export type UserListParams = {
  page: number;
  size: number;
  sort: "code" | "employeeNumber" | "name" | "status";
  search?: string;
  status?: UserStatus
};

function usersPath(companyCode: string) {
  return `/api/v1/admin/companies/${resourceCode(companyCode, "회사 코드")}/users`;
}

function userPath(companyCode: string, userCode: string) {
  return `${usersPath(companyCode)}/${resourceCode(userCode, "사용자 코드")}`;
}

function normalizedInput(input: UserInput) {
  return {
    ...input,
    code: decodeURIComponent(resourceCode(input.code, "사용자 코드")),
    employeeNumber: input.employeeNumber.trim(),
    name: input.name.trim(),
    loginEmail: input.loginEmail.trim().toLowerCase(),
    phone: input.phone.trim(),
    workplace: input.workplace.trim(),
    profileImageUrl: input.profileImageUrl.trim(),
    positionCode: decodeURIComponent(resourceCode(input.positionCode, "직위 코드"))
  };
}

export const userApi = {
  list: (companyCode: string, params: UserListParams, signal?: AbortSignal) => {
    const query = new URLSearchParams({page: String(params.page), size: String(params.size), sort: params.sort});
    if (params.search?.trim()) query.set("search", params.search.trim());
    if (params.status) query.set("status", params.status);
    return apiClient.request<PageResponse<User>>(`${usersPath(companyCode)}?${query}`, {signal});
  },
  find: (companyCode: string, userCode: string, signal?: AbortSignal) => apiClient.request<User>(userPath(companyCode, userCode), {signal}),
  create: (companyCode: string, input: UserInput, signal?: AbortSignal) => apiClient.request<{
    user: User;
    temporaryPassword: string
  }>(usersPath(companyCode), {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(normalizedInput(input)),
    signal
  }),
  update: (companyCode: string, userCode: string, input: Omit<UserInput, "code" | "employeeNumber" | "loginEmail"> & {
    version: number
  }, signal?: AbortSignal) => apiClient.request<User>(userPath(companyCode, userCode), {
    method: "PUT",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({
      name: input.name.trim(),
      phone: input.phone.trim(),
      hiredAt: input.hiredAt,
      workplace: input.workplace.trim(),
      profileImageUrl: input.profileImageUrl.trim(),
      positionCode: decodeURIComponent(resourceCode(input.positionCode, "직위 코드")),
      version: input.version
    }),
    signal
  }),
  changeStatus: (companyCode: string, userCode: string, status: UserStatus, version: number) => apiClient.request<User>(`${userPath(companyCode, userCode)}/status`, {
    method: "PUT",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({status, version})
  }),
  resetPassword: (companyCode: string, userCode: string, signal?: AbortSignal) => apiClient.request<{
    temporaryPassword: string
  }>(`${userPath(companyCode, userCode)}/temporary-password`, {method: "POST", signal}),
  grantAdmin: (companyCode: string, userCode: string) => apiClient.request<void>(`${userPath(companyCode, userCode)}/admin-role`, {method: "PUT"}),
  revokeAdmin: (companyCode: string, userCode: string) => apiClient.request<void>(`${userPath(companyCode, userCode)}/admin-role`, {method: "DELETE"}),
  memberships: (companyCode: string, userCode: string, signal?: AbortSignal) => apiClient.request<PageResponse<Membership>>(`${userPath(companyCode, userCode)}/memberships?page=0&size=100&sort=startedAt`, {signal}),
  membershipsAll: async (companyCode: string, userCode: string, signal?: AbortSignal) => {
    const base = `${userPath(companyCode, userCode)}/memberships`;
    const first = await apiClient.request<PageResponse<Membership>>(`${base}?page=0&size=100&sort=startedAt`, {signal});
    const rest = await Promise.all(Array.from({length: Math.max(0, first.totalPages - 1)}, (_, index) =>
        apiClient.request<PageResponse<Membership>>(`${base}?page=${index + 1}&size=100&sort=startedAt`, {signal})));
    return [first, ...rest].flatMap((page) => page.content);
  },
  assignMembership: (companyCode: string, userCode: string, input: {
    departmentCode: string;
    role: MembershipRole;
    primary: boolean;
    startedAt: string
  }) => apiClient.request<Membership>(`${userPath(companyCode, userCode)}/memberships`, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({...input, departmentCode: decodeURIComponent(resourceCode(input.departmentCode, "부서 코드"))})
  }),
  updateMembership: (companyCode: string, userCode: string, membershipId: number, input: {
    role: MembershipRole;
    primary: boolean;
    version: number
  }) => apiClient.request<Membership>(`${userPath(companyCode, userCode)}/memberships/${encodeURIComponent(String(membershipId))}`, {
    method: "PUT",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(input)
  }),
  endMembership: (companyCode: string, userCode: string, membershipId: number, version: number) => apiClient.request<Membership>(`${userPath(companyCode, userCode)}/memberships/${encodeURIComponent(String(membershipId))}?version=${encodeURIComponent(String(version))}`, {method: "DELETE"}),
};

export type AdminActorRole = Extract<Role, "SYSTEM_ADMIN" | "COMPANY_ADMIN">;
