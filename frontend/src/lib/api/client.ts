import {
  authOperationLock,
  type AuthOperationLock,
} from "@/features/auth/auth-operation-lock";
import {
  authSession,
  type AuthSessionValue,
  type MemoryAuthSession,
} from "@/features/auth/auth-session";
import { createSingleFlight } from "@/features/auth/single-flight-refresh";
import { ApiProblemError, type ApiProblem } from "./problem";

export type TokenResponse = {
  accessToken: string;
  accessTokenExpiresAt: string;
  mustChangePassword: boolean;
};

export type RefreshCommit = {
  token: TokenResponse;
  session: AuthSessionValue;
};

type ApiRequestOptions = {
  authenticate?: boolean;
  refreshOnUnauthorized?: boolean;
  allowPasswordChangeToken?: boolean;
};

type ApiClientOptions = {
  session?: MemoryAuthSession;
  authLock?: AuthOperationLock;
};

const passwordEndpoint = "/api/v1/auth/password";

export class StaleAuthOperationError extends Error {
  constructor() {
    super("The authentication session changed while the request was in flight.");
    this.name = "StaleAuthOperationError";
  }
}

function absoluteUrl(path: string): string {
  if (/^https?:\/\//.test(path)) return path;
  const origin = typeof window === "undefined" ? "http://localhost:3000" : window.location.origin;
  return new URL(path, origin).toString();
}

function localProblem(status: number, code: string, title: string, detail: string) {
  return new ApiProblemError({
    type: `https://auth-study.local/problems/${code.toLowerCase().replaceAll("_", "-")}`,
    title,
    status,
    detail,
    code,
    traceId: "client",
    fieldErrors: [],
  });
}

async function problemFrom(response: Response): Promise<ApiProblemError> {
  const fallback: ApiProblem = {
    type: "about:blank",
    title: response.statusText || "Request failed",
    status: response.status,
    detail: response.statusText || "The request could not be completed.",
    code: `HTTP_${response.status}`,
    traceId: response.headers.get("X-Trace-Id") ?? "unknown",
    fieldErrors: [],
  };
  try {
    const body = (await response.json()) as Partial<ApiProblem>;
    return new ApiProblemError({
      ...fallback,
      ...body,
      status: body.status ?? response.status,
      fieldErrors: Array.isArray(body.fieldErrors) ? body.fieldErrors : [],
    });
  } catch {
    return new ApiProblemError(fallback);
  }
}

async function valueFrom<T>(response: Response): Promise<T> {
  if (!response.ok) throw await problemFrom(response);
  if (response.status === 204 || response.headers.get("Content-Length") === "0") {
    return undefined as T;
  }
  const contentType = response.headers.get("Content-Type") ?? "";
  if (!contentType.includes("json")) return (await response.text()) as T;
  return (await response.json()) as T;
}

function ensureReplayable(body: BodyInit | null | undefined): void {
  if (typeof ReadableStream !== "undefined" && body instanceof ReadableStream) {
    throw new TypeError("Streaming request bodies cannot be retried safely.");
  }
}

export function createApiClient(options: ApiClientOptions = {}) {
  const session = options.session ?? authSession;
  const lock = options.authLock ?? authOperationLock;
  const runRefresh = createSingleFlight<RefreshCommit>();

  const send = (
    path: string,
    init: RequestInit,
    accessToken: string | null,
    authenticate: boolean,
  ) => {
    const headers = new Headers(init.headers);
    if (!headers.has("Accept")) headers.set("Accept", "application/json");
    if (authenticate && accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
    return fetch(absoluteUrl(path), {
      ...init,
      headers,
      credentials: init.credentials ?? "same-origin",
    });
  };

  async function rawLogout(accessToken: string | null): Promise<void> {
    const response = await send(
      "/api/v1/auth/logout",
      { method: "POST" },
      accessToken,
      true,
    );
    return valueFrom<void>(response);
  }

  async function refreshAccessToken(
    expectedSession: AuthSessionValue = session.get(),
  ): Promise<RefreshCommit> {
    return runRefresh(async () => {
      if (!session.isCurrent(expectedSession.generation)) throw new StaleAuthOperationError();
      try {
        return await lock.runExclusive(async () => {
          if (!session.isCurrent(expectedSession.generation)) throw new StaleAuthOperationError();
          const response = await send(
            "/api/v1/auth/refresh",
            { method: "POST" },
            null,
            false,
          );
          if (!response.ok) throw await problemFrom(response);
          const token = await valueFrom<TokenResponse>(response);
          const committed = session.compareAndSet(
            expectedSession.generation,
            token.accessToken,
            "authenticated",
            "refresh",
          );
          if (!committed) {
            if (session.get().mode === "anonymous") {
              try {
                await rawLogout(token.accessToken);
              } catch {
                // A stale refresh must never restore local auth; cookie cleanup is best-effort.
              }
            }
            throw new StaleAuthOperationError();
          }
          return { token, session: committed };
        });
      } catch (error) {
        session.clearIfCurrent(expectedSession.generation);
        throw error;
      }
    });
  }

  async function request<T>(
    path: string,
    init: RequestInit = {},
    requestOptions: ApiRequestOptions = {},
  ): Promise<T> {
    ensureReplayable(init.body);
    const authenticate = requestOptions.authenticate ?? true;
    const refreshOnUnauthorized = requestOptions.refreshOnUnauthorized ?? authenticate;
    const initialSession = session.get();

    if (
      authenticate &&
      initialSession.mode === "passwordChangeRequired" &&
      !requestOptions.allowPasswordChangeToken &&
      new URL(absoluteUrl(path)).pathname !== passwordEndpoint
    ) {
      throw localProblem(
        403,
        "PASSWORD_CHANGE_REQUIRED",
        "Password change required",
        "Change your password before using other APIs.",
      );
    }

    const tokenUsed = authenticate ? initialSession.accessToken : null;
    let response = await send(path, init, tokenUsed, authenticate);
    if (response.status !== 401 || !refreshOnUnauthorized) return valueFrom<T>(response);
    if (init.signal?.aborted) throw new DOMException("The operation was aborted.", "AbortError");

    let retrySession: AuthSessionValue;
    const currentSession = session.get();
    if (currentSession.generation !== initialSession.generation) {
      if (!session.isRefreshSuccessorOf(initialSession.generation)) {
        throw new StaleAuthOperationError();
      }
      retrySession = currentSession;
    } else {
      retrySession = (await refreshAccessToken(initialSession)).session;
    }

    response = await send(path, init, retrySession.accessToken, authenticate);
    if (response.status === 401) session.clearIfCurrent(retrySession.generation);
    return valueFrom<T>(response);
  }

  async function requestWithAccessToken<T>(
    path: string,
    accessToken: string,
    init: RequestInit = {},
  ): Promise<T> {
    ensureReplayable(init.body);
    return valueFrom<T>(await send(path, init, accessToken, true));
  }

  async function runLoginTransaction<T>(
    init: RequestInit,
    isCurrent: () => boolean,
    complete: (token: TokenResponse) => Promise<T>,
  ): Promise<T> {
    if (!isCurrent()) throw new StaleAuthOperationError();
    return lock.runExclusive(async () => {
      if (!isCurrent()) throw new StaleAuthOperationError();
      let provisionalToken: TokenResponse | null = null;
      try {
        provisionalToken = await request<TokenResponse>(
          "/api/v1/auth/login",
          init,
          { authenticate: false, refreshOnUnauthorized: false },
        );
        if (!isCurrent()) throw new StaleAuthOperationError();
        return await complete(provisionalToken);
      } catch (error) {
        if (provisionalToken && !provisionalToken.mustChangePassword) {
          try {
            await rawLogout(provisionalToken.accessToken);
          } catch {
            // Preserve the login/actor failure; local session ownership is handled by the caller.
          }
        }
        throw error;
      }
    });
  }

  async function logout(accessToken: string | null): Promise<void> {
    return lock.runExclusive(() => rawLogout(accessToken));
  }

  return { request, requestWithAccessToken, runLoginTransaction, refreshAccessToken, logout };
}

export const apiClient = createApiClient();
