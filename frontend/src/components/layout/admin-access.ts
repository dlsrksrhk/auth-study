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
  if (roles.includes("SYSTEM_ADMIN")) return null;
  if (roles.includes("COMPANY_ADMIN")) {
    if (!companyCode) return "/account";
    if (pathname === "/companies") return "/";
    const match = pathname.match(/^\/companies\/([^/]+)(\/.*)?$/);
    if (match && match[1].toUpperCase() !== companyCode.toUpperCase()) {
      return `/companies/${companyCode.toUpperCase()}${match[2] ?? "/positions"}`;
    }
    return null;
  }
  return "/account";
}

export function resolveAdminCompanyCode({
  roles,
  actorCompanyCode,
  urlCompanyCode,
}: {
  roles: Role[];
  actorCompanyCode: string | null;
  urlCompanyCode: string | null;
}): { companyCode: string | null; fixed: boolean } {
  if (roles.includes("SYSTEM_ADMIN")) {
    return { companyCode: urlCompanyCode?.trim().toUpperCase() || null, fixed: false };
  }
  if (roles.includes("COMPANY_ADMIN")) {
    return { companyCode: actorCompanyCode?.trim().toUpperCase() || null, fixed: true };
  }
  return { companyCode: null, fixed: false };
}
