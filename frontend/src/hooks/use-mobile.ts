import * as React from "react";

const MOBILE_BREAKPOINT = 768;

function subscribe(listener: () => void) {
  const mediaQuery = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`);
  mediaQuery.addEventListener("change", listener);
  return () => mediaQuery.removeEventListener("change", listener);
}

function getSnapshot() {
  return window.innerWidth < MOBILE_BREAKPOINT;
}

export function useIsMobile() {
  return React.useSyncExternalStore(subscribe, getSnapshot, () => false);
}
