export const AUTH_OPERATION_LOCK_NAME = "auth-study-refresh-rotation";

export type AuthOperationLock = {
  runExclusive<T>(operation: () => Promise<T>): Promise<T>;
};

export type WebLockManagerLike = {
  request<T>(name: string, callback: () => Promise<T>): Promise<T>;
};

const fallbackTails = new Map<string, Promise<void>>();

function browserLockManager(): WebLockManagerLike | null {
  if (typeof navigator === "undefined" || !navigator.locks) return null;
  return {
    async request<T>(name: string, callback: () => Promise<T>): Promise<T> {
      return await navigator.locks.request(
        name,
        { mode: "exclusive" },
        async () => await callback(),
      );
    },
  };
}

async function runFallback<T>(name: string, operation: () => Promise<T>): Promise<T> {
  const previous = fallbackTails.get(name) ?? Promise.resolve();
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const tail = previous.catch(() => undefined).then(() => gate);
  fallbackTails.set(name, tail);
  await previous.catch(() => undefined);
  try {
    return await operation();
  } finally {
    release();
    if (fallbackTails.get(name) === tail) fallbackTails.delete(name);
  }
}

export function createAuthOperationLock(
  lockManager: WebLockManagerLike | null = browserLockManager(),
): AuthOperationLock {
  return {
    runExclusive: (operation) =>
      lockManager
        ? lockManager.request(AUTH_OPERATION_LOCK_NAME, operation)
        : runFallback(AUTH_OPERATION_LOCK_NAME, operation),
  };
}

export const authOperationLock = createAuthOperationLock();
