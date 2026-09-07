import {describe, expect, it} from "vitest";

import {validateNewPassword} from "./password-validation";

describe("validateNewPassword", () => {
  it.each([
    ["Short1!a", "12자 이상"],
    [`Aa1!${"a".repeat(61)}`, "64자 이하"],
    [`Aa1!${"가".repeat(23)}`, "UTF-8 72바이트 이하"],
    ["lowercase123!", "영문 대문자"],
    ["UPPERCASE123!", "영문 소문자"],
    ["NoNumberHere!", "숫자"],
    ["NoSpecial1234", "특수문자"],
  ])("rejects backend-incompatible password %s", (password, message) => {
    expect(validateNewPassword(password).some((error) => error.includes(message))).toBe(true);
  });

  it("accepts 12+ code points with four character groups within the BCrypt byte limit", () => {
    expect(validateNewPassword("ValidPassword1!")).toEqual([]);
  });
});
