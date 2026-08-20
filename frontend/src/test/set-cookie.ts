export type HeaderEntry = {
  name: string;
  value: string;
};

export type ParsedSetCookie = {
  name: string;
  value: string;
  attributes: Map<string, string | true>;
};

export type RefreshCookieMode = "issued" | "deleted";

const refreshCookieName = "AUTH_STUDY_REFRESH";
const refreshCookiePath = "/api/v1/auth";

export function findSetCookiesByName(headers: HeaderEntry[], cookieName: string): ParsedSetCookie[] {
  return headers
    .filter((header) => header.name.toLowerCase() === "set-cookie")
    .map((header) => parseSetCookie(header.value))
    .filter((cookie) => cookie.name === cookieName);
}

export function parseSetCookie(value: string): ParsedSetCookie {
  const [nameValue, ...rawAttributes] = value.split(";");
  const separator = nameValue.indexOf("=");
  if (separator <= 0) throw new Error(`Invalid Set-Cookie header: ${value}`);

  const attributes = new Map<string, string | true>();
  for (const rawAttribute of rawAttributes) {
    const attribute = rawAttribute.trim();
    if (!attribute) continue;

    const equals = attribute.indexOf("=");
    if (equals === -1) {
      attributes.set(attribute.toLowerCase(), true);
    } else {
      attributes.set(attribute.slice(0, equals).trim().toLowerCase(), attribute.slice(equals + 1).trim());
    }
  }

  return {
    name: nameValue.slice(0, separator).trim(),
    value: nameValue.slice(separator + 1),
    attributes,
  };
}

export function requireSingleRefreshCookie(
  cookies: ParsedSetCookie[],
  mode: RefreshCookieMode,
): ParsedSetCookie {
  if (cookies.length !== 1) {
    throw new Error(`Expected exactly one ${refreshCookieName} Set-Cookie header, received ${cookies.length}`);
  }
  validateRefreshCookie(cookies[0], mode);
  return cookies[0];
}

export function validateRefreshCookie(cookie: ParsedSetCookie, mode: RefreshCookieMode): void {
  if (cookie.name !== refreshCookieName) throw new Error(`Unexpected cookie name: ${cookie.name}`);
  if (cookie.attributes.get("httponly") !== true) throw new Error("Refresh cookie must be HttpOnly");

  const sameSite = cookie.attributes.get("samesite");
  if (typeof sameSite !== "string" || sameSite.toLowerCase() !== "lax") {
    throw new Error("Refresh cookie SameSite must be Lax");
  }
  if (cookie.attributes.get("path") !== refreshCookiePath) {
    throw new Error(`Refresh cookie Path must be ${refreshCookiePath}`);
  }
  if (cookie.attributes.has("secure")) throw new Error("Refresh cookie must omit Secure for local HTTP");

  const maxAge = cookie.attributes.get("max-age");
  if (typeof maxAge !== "string") throw new Error("Refresh cookie Max-Age must have a value");

  if (mode === "deleted") {
    if (cookie.value !== "") throw new Error("Deleted refresh cookie value must be empty");
    if (maxAge !== "0") throw new Error('Deleted refresh cookie Max-Age must be exactly "0"');
    return;
  }

  if (cookie.value.length === 0) throw new Error("Issued refresh cookie value must not be empty");
  if (!/^[1-9]\d*$/.test(maxAge)) {
    throw new Error("Issued refresh cookie Max-Age must be a canonical positive integer");
  }
  const maxAgeSeconds = Number(maxAge);
  if (!Number.isSafeInteger(maxAgeSeconds)) {
    throw new Error("Issued refresh cookie Max-Age must be a safe integer");
  }
}
