import type { AuthStatus } from "@/features/auth/auth-provider";
import type { Role } from "@/features/auth/auth-api";

type AdminAccess = {
  status: AuthStatus;
  roles: Role[];
  companyCode: string | null;
  pathname: string;
};

export function resolveAdminRedirect({ status, roles, companyCode, pathname }: AdminAccess): string | null {
  if (status === "loading") return null;
  if (status === "anonymous") return "/login";
  if (status === "passwordChangeRequired") return "/change-password";
  if (roles.includes("USER") && !roles.some((role) => role === "SYSTEM_ADMIN" || role === "COMPANY_ADMIN")) {
    return "/account";
  }
  if (roles.includes("SYSTEM_ADMIN")) return null;
  if (roles.includes("COMPANY_ADMIN")) {
    if (pathname === "/companies" || !companyCode) return "/";
    const match = pathname.match(/^\/companies\/([^/]+)(\/.*)?$/);
    if (match && match[1].toUpperCase() !== companyCode.toUpperCase()) {
      return `/companies/${companyCode.toUpperCase()}${match[2] ?? "/positions"}`;
    }
  }
  return null;
}
