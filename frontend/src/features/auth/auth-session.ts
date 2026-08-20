export type AuthMode = "anonymous" | "authenticated" | "passwordChangeRequired";
export type AuthMutation = "clear" | "interactive" | "refresh";

export type AuthSessionValue = {
  accessToken: string | null;
  mode: AuthMode;
  generation: number;
};

type Listener = (value: AuthSessionValue) => void;
type Transition = { from: number; to: number; mutation: AuthMutation };

export type MemoryAuthSession = ReturnType<typeof createMemoryAuthSession>;

export function createMemoryAuthSession() {
  let value: AuthSessionValue = { accessToken: null, mode: "anonymous", generation: 0 };
  let lastTransition: Transition | null = null;
  const listeners = new Set<Listener>();

  function replace(
    accessToken: string | null,
    mode: AuthMode,
    mutation: AuthMutation,
  ): AuthSessionValue {
    const from = value.generation;
    value = { accessToken, mode, generation: from + 1 };
    lastTransition = { from, to: value.generation, mutation };
    listeners.forEach((listener) => listener(value));
    return value;
  }

  return {
    get: () => value,
    set: (
      accessToken: string,
      mode: Exclude<AuthMode, "anonymous">,
      mutation: AuthMutation = "interactive",
    ) => replace(accessToken, mode, mutation),
    compareAndSet: (
      expectedGeneration: number,
      accessToken: string,
      mode: Exclude<AuthMode, "anonymous">,
      mutation: AuthMutation = "refresh",
    ) =>
      value.generation === expectedGeneration
        ? replace(accessToken, mode, mutation)
        : null,
    clear: () => replace(null, "anonymous", "clear"),
    clearIfCurrent: (expectedGeneration: number) => {
      if (value.generation !== expectedGeneration) return false;
      replace(null, "anonymous", "clear");
      return true;
    },
    isCurrent: (expectedGeneration: number) => value.generation === expectedGeneration,
    isRefreshSuccessorOf: (generation: number) =>
      lastTransition?.mutation === "refresh" &&
      lastTransition.from === generation &&
      lastTransition.to === value.generation,
    subscribe: (listener: Listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export const authSession = createMemoryAuthSession();
