import { authSession, type MemoryAuthSession } from "@/features/auth/auth-session";
import { createSingleFlight } from "@/features/auth/single-flight-refresh";
import { ApiProblemError, type ApiProblem } from "./problem";

type TokenResponse = {
  accessToken: string;
  accessTokenExpiresAt: string;
  mustChangePassword: boolean;
};

type ApiRequestOptions = {
  authenticate?: boolean;
  refreshOnUnauthorized?: boolean;
  allowPasswordChangeToken?: boolean;
};

type ApiClientOptions = {
  session?: MemoryAuthSession;
};

const passwordEndpoint = "/api/v1/auth/password";

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
  const runRefresh = createSingleFlight<TokenResponse>();

  async function refreshAccessToken(): Promise<TokenResponse> {
    return runRefresh(async () => {
      const response = await fetch(absoluteUrl("/api/v1/auth/refresh"), {
        method: "POST",
        credentials: "same-origin",
        headers: { Accept: "application/json" },
      });
      if (!response.ok) throw await problemFrom(response);
      const token = await valueFrom<TokenResponse>(response);
      session.set(token.accessToken, "authenticated");
      return token;
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

    const send = (accessToken: string | null) => {
      const headers = new Headers(init.headers);
      if (!headers.has("Accept")) headers.set("Accept", "application/json");
      if (authenticate && accessToken) headers.set("Authorization", `Bearer ${accessToken}`);
      return fetch(absoluteUrl(path), {
        ...init,
        headers,
        credentials: init.credentials ?? "same-origin",
      });
    };

    const tokenUsed = authenticate ? initialSession.accessToken : null;
    let response = await send(tokenUsed);
    if (response.status !== 401 || !refreshOnUnauthorized) return valueFrom<T>(response);
    if (init.signal?.aborted) throw new DOMException("The operation was aborted.", "AbortError");

    try {
      const latestToken = session.get().accessToken;
      if (!latestToken || latestToken === tokenUsed) await refreshAccessToken();
      response = await send(session.get().accessToken);
    } catch (error) {
      session.clear();
      throw error;
    }

    if (response.status === 401) session.clear();
    return valueFrom<T>(response);
  }

  return { request, refreshAccessToken };
}

export const apiClient = createApiClient();
