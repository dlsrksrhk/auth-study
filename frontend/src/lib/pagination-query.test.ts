import {describe, expect, it} from "vitest";

import {parseListQuery} from "./pagination-query";

describe("parseListQuery", () => {
  it.each(["Infinity", "1e2", "1.5", "-1", "2147483647", "+1", " 1"])(
      "canonicalizes an unsafe page value %s",
      (page) => {
        const parsed = parseListQuery(new URLSearchParams({page}), {
          defaultSort: "code",
          sorts: ["code", "name", "status"],
          enumKey: "status",
          enumValues: ["ACTIVE", "INACTIVE"],
        });
        expect(parsed.page).toBe(0);
        expect(parsed.needsReplace).toBe(true);
        expect(parsed.canonical.toString()).toBe("");
      },
  );

  it.each(["0", "19", "21", "101", "Infinity", "1e2", "20.0"])(
      "canonicalizes a disallowed size value %s",
      (size) => {
        const parsed = parseListQuery(new URLSearchParams({size}), {
          defaultSort: "displayOrder",
          sorts: ["code", "name", "level", "displayOrder"],
          enumKey: "active",
          enumValues: ["true", "false"],
        });
        expect(parsed.size).toBe(20);
        expect(parsed.needsReplace).toBe(size !== "20");
      },
  );

  it("removes bogus enum and sort while retaining valid search and paging", () => {
    const parsed = parseListQuery(new URLSearchParams("page=2&size=50&sort=bogus&status=PAUSED&search=acme"), {
      defaultSort: "code",
      sorts: ["code", "name", "status"],
      enumKey: "status",
      enumValues: ["ACTIVE", "INACTIVE"],
    });
    expect(parsed).toMatchObject({page: 2, size: 50, sort: "code", enumValue: undefined, needsReplace: true});
    expect(parsed.canonical.toString()).toBe("search=acme&page=2&size=50");
  });

  it("accepts the largest page whose backend offset remains safe", () => {
    const parsed = parseListQuery(new URLSearchParams("page=21474836&size=100"), {
      defaultSort: "code",
      sorts: ["code"],
    });
    expect(parsed.page).toBe(0);
    expect(parsed.needsReplace).toBe(true);
  });
});
