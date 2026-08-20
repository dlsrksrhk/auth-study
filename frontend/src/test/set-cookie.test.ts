import { describe, expect, it } from "vitest";

import {
  findSetCookiesByName,
  requireSingleRefreshCookie,
  validateRefreshCookie,
  type ParsedSetCookie,
} from "@/test/set-cookie";

const refreshName = "AUTH_STUDY_REFRESH";

function refreshCookie(maxAge: string, value = "refresh-token"): ParsedSetCookie {
  return {
    name: refreshName,
    value,
    attributes: new Map<string, string | true>([
      ["httponly", true],
      ["samesite", "Lax"],
      ["path", "/api/v1/auth"],
      ["max-age", maxAge],
    ]),
  };
}

describe("findSetCookiesByName", () => {
  it("parses every Set-Cookie header and returns only exact-name matches", () => {
    const cookies = findSetCookiesByName(
      [
        { name: "Content-Type", value: "application/json" },
        { name: "Set-Cookie", value: "OTHER=value; Path=/" },
        { name: "set-cookie", value: `${refreshName}=first; HttpOnly; Path=/api/v1/auth` },
        { name: "SET-COOKIE", value: `auth_study_refresh=wrong-case; HttpOnly` },
        { name: "Set-Cookie", value: `${refreshName}=second; HttpOnly; Path=/api/v1/auth` },
      ],
      refreshName,
    );

    expect(cookies.map((cookie) => cookie.value)).toEqual(["first", "second"]);
  });
});

describe("requireSingleRefreshCookie", () => {
  it("rejects duplicate exact-name cookies", () => {
    expect(() => requireSingleRefreshCookie([refreshCookie("60"), refreshCookie("60")], "issued"))
      .toThrow(/exactly one/i);
  });

  it("rejects a missing cookie", () => {
    expect(() => requireSingleRefreshCookie([], "issued")).toThrow(/exactly one/i);
  });
});

describe("validateRefreshCookie Max-Age grammar", () => {
  it.each(["", "+0", "0", "0e9", "1e3", "0x10", "01"])(
    "rejects non-canonical issued Max-Age %j",
    (maxAge) => {
      expect(() => validateRefreshCookie(refreshCookie(maxAge), "issued")).toThrow(/Max-Age/);
    },
  );

  it("accepts a canonical positive issued Max-Age", () => {
    expect(() => validateRefreshCookie(refreshCookie("604800"), "issued")).not.toThrow();
  });

  it.each(["", "+0", "0e9", "1e3", "0x10", "01", "1"])(
    "rejects non-canonical deleted Max-Age %j",
    (maxAge) => {
      expect(() => validateRefreshCookie(refreshCookie(maxAge, ""), "deleted")).toThrow(/Max-Age/);
    },
  );

  it("accepts exactly zero for a deleted Max-Age", () => {
    expect(() => validateRefreshCookie(refreshCookie("0", ""), "deleted")).not.toThrow();
  });
});
