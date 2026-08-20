export type AuthMode = "anonymous" | "authenticated" | "passwordChangeRequired";

export type AuthSessionValue = {
  accessToken: string | null;
  mode: AuthMode;
};

type Listener = (value: AuthSessionValue) => void;

export type MemoryAuthSession = ReturnType<typeof createMemoryAuthSession>;

export function createMemoryAuthSession() {
  let value: AuthSessionValue = { accessToken: null, mode: "anonymous" };
  const listeners = new Set<Listener>();

  return {
    get: () => value,
    set: (accessToken: string, mode: Exclude<AuthMode, "anonymous">) => {
      value = { accessToken, mode };
      listeners.forEach((listener) => listener(value));
    },
    clear: () => {
      value = { accessToken: null, mode: "anonymous" };
      listeners.forEach((listener) => listener(value));
    },
    subscribe: (listener: Listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export const authSession = createMemoryAuthSession();
