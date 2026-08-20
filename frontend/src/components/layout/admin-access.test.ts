import { describe, expect, it } from "vitest";

import { resolveAdminRedirect } from "./admin-access";

describe("resolveAdminRedirect", () => {
  it.each([
    ["anonymous", [], null, "/companies", "/login"],
    ["passwordChangeRequired", [], null, "/companies", "/change-password"],
    ["authenticated", ["USER"], "ACME", "/", "/account"],
    ["authenticated", ["COMPANY_ADMIN"], "ACME", "/companies/OTHER/positions", "/companies/ACME/positions"],
    ["authenticated", ["COMPANY_ADMIN"], "ACME", "/companies", "/"],
  ] as const)("redirects %s from %s", (status, roles, companyCode, pathname, expected) => {
    expect(resolveAdminRedirect({ status, roles: [...roles], companyCode, pathname })).toBe(expected);
  });

  it("allows a company administrator to reach only their own company route", () => {
    expect(resolveAdminRedirect({
      status: "authenticated",
      roles: ["COMPANY_ADMIN"],
      companyCode: "ACME",
      pathname: "/companies/ACME/positions",
    })).toBeNull();
  });

  it("gives system administration precedence when roles are combined", () => {
    expect(resolveAdminRedirect({
      status: "authenticated",
      roles: ["USER", "COMPANY_ADMIN", "SYSTEM_ADMIN"],
      companyCode: "ACME",
      pathname: "/companies/OTHER/positions",
    })).toBeNull();
  });
});
