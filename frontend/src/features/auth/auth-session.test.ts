import { describe, expect, it } from "vitest";

import { createMemoryAuthSession } from "./auth-session";

describe("memory auth session", () => {
  it("rejects stale token commits and clears using a monotonic generation", () => {
    const session = createMemoryAuthSession();
    const initial = session.get();
    const login = session.set("login-token", "authenticated");

    expect(login.generation).toBeGreaterThan(initial.generation);
    expect(session.compareAndSet(initial.generation, "stale-refresh", "authenticated", "refresh")).toBeNull();
    expect(session.clearIfCurrent(initial.generation)).toBe(false);
    expect(session.get()).toEqual(login);
  });
});
