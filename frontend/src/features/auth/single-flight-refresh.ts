export function createSingleFlight<T>() {
  let pending: Promise<T> | null = null;

  return (operation: () => Promise<T>): Promise<T> => {
    if (pending) return pending;
    pending = operation().finally(() => {
      pending = null;
    });
    return pending;
  };
}
