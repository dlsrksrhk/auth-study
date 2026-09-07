"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { useAuth } from "@/features/auth/auth-provider";
import { isApiProblemError } from "@/lib/api/problem";
import { resourceCode } from "@/lib/resource-code";
import {
  oauthClientApi,
  type OAuthClientInput,
  type OAuthScope,
} from "./oauth-client-api";
import { oauthScopeCatalog } from "./oauth-scope-catalog";
import { useOAuthSecretOperations } from "./oauth-secret-operation-provider";

type Field = keyof OAuthClientInput;
type FieldErrors = Partial<Record<Field, string>>;

// Validate the original strings; URL serialization would silently change exact redirects.
export function parseRedirectUris(text: string, required: boolean): string[] {
  if (text === "") {
    if (required) throw new Error("Redirect URI를 하나 이상 입력해 주세요.");
    return [];
  }
  const uris = text.split(/\r?\n/);
  const seen = new Set<string>();
  for (const uri of uris) {
    if (!uri) throw new Error("URI 사이의 빈 줄을 제거해 주세요.");
    if (/\s/.test(uri)) throw new Error("URI에는 공백을 사용할 수 없습니다.");
    if (seen.has(uri)) throw new Error("중복된 URI를 제거해 주세요.");
    if (uri.includes("#"))
      throw new Error("URI에는 fragment(#)를 사용할 수 없습니다.");
    let parsed: URL;
    try {
      parsed = new URL(uri);
    } catch {
      throw new Error("올바른 절대 URI를 입력해 주세요.");
    }
    if (
      !/^https?:\/\//i.test(uri) ||
      !["http:", "https:"].includes(parsed.protocol)
    )
      throw new Error("http 또는 https URI를 입력해 주세요.");
    if (parsed.username || parsed.password || /^https?:\/\/[^/?#]*@/i.test(uri))
      throw new Error("URI에 사용자 정보를 포함할 수 없습니다.");
    if (uri.includes("\\")) throw new Error("올바른 절대 URI를 입력해 주세요.");
    seen.add(uri);
  }
  return uris;
}

type Props = {
  initialValues?: OAuthClientInput;
  canSetTrust: boolean;
  clientTypeReadOnly?: boolean;
  onSubmit(input: OAuthClientInput): Promise<void>;
};

export function OAuthClientForm({
  initialValues,
  canSetTrust,
  clientTypeReadOnly = false,
  onSubmit,
}: Props) {
  const [displayName, setDisplayName] = useState(
    initialValues?.displayName ?? "",
  );
  const [publicClient, setPublicClient] = useState(
    initialValues?.publicClient ?? true,
  );
  const [redirectUris, setRedirectUris] = useState(
    initialValues?.redirectUris.join("\n") ?? "",
  );
  const [postLogoutRedirectUris, setPostLogoutRedirectUris] = useState(
    initialValues?.postLogoutRedirectUris.join("\n") ?? "",
  );
  const [scopes, setScopes] = useState<OAuthScope[]>(
    initialValues?.scopes ?? ["openid"],
  );
  const [trust, setTrust] = useState(
    initialValues?.trust ?? "CONSENT_REQUIRED",
  );
  const [pending, setPending] = useState(false);
  const submitting = useRef(false);
  const form = useRef<HTMLFormElement>(null);
  const [errors, setErrors] = useState<FieldErrors>({});
  const [globalError, setGlobalError] = useState<string | null>(null);

  useEffect(() => {
    const field = Object.keys(errors)[0];
    if (!pending && field)
      form.current
        ?.querySelector<HTMLElement>(`[name="${field}"]:not(:disabled)`)
        ?.focus();
  }, [errors, pending]);

  function showErrors(next: FieldErrors) {
    setErrors(next);
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (submitting.current) return;
    const next: FieldErrors = {};
    if (!displayName.trim()) next.displayName = "표시 이름을 입력해 주세요.";
    let redirects: string[] = [];
    let logoutRedirects: string[] = [];
    try {
      redirects = parseRedirectUris(redirectUris, true);
    } catch (cause) {
      next.redirectUris = (cause as Error).message;
    }
    try {
      logoutRedirects = parseRedirectUris(postLogoutRedirectUris, false);
    } catch (cause) {
      next.postLogoutRedirectUris = (cause as Error).message;
    }
    setGlobalError(null);
    showErrors(next);
    if (Object.keys(next).length) return;
    submitting.current = true;
    setPending(true);
    try {
      await onSubmit({
        displayName,
        publicClient,
        redirectUris: redirects,
        postLogoutRedirectUris: logoutRedirects,
        scopes: (Object.keys(oauthScopeCatalog) as OAuthScope[]).filter(
          (scope) => scope === "openid" || scopes.includes(scope),
        ),
        trust: canSetTrust ? trust : "CONSENT_REQUIRED",
      });
    } catch (cause) {
      if (isApiProblemError(cause)) {
        setGlobalError(
          `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})`,
        );
        const mapped: FieldErrors = {};
        for (const item of cause.fieldErrors) {
          const field = item.field.split(/[.\[]/)[0] as Field;
          if (
            [
              "displayName",
              "publicClient",
              "redirectUris",
              "postLogoutRedirectUris",
              "scopes",
              "trust",
            ].includes(field)
          )
            mapped[field] = item.message;
        }
        showErrors(mapped);
      } else
        setGlobalError(
          "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.",
        );
    } finally {
      submitting.current = false;
      setPending(false);
    }
  }

  function fieldProps(field: Field) {
    return {
      id: `oauth-${field}`,
      name: field,
      "aria-invalid": Boolean(errors[field]),
      "aria-describedby": errors[field] ? `oauth-${field}-error` : undefined,
    };
  }

  return (
    <form
      ref={form}
      onSubmit={submit}
      noValidate
      className="mt-6 space-y-5 rounded-xl border bg-white p-6 shadow-sm"
      aria-busy={pending}
    >
      {globalError ? (
        <p
          role="alert"
          className="rounded-md border border-red-200 p-3 text-sm text-red-700"
        >
          {globalError}
        </p>
      ) : null}
      <fieldset disabled={pending} className="space-y-5">
        <Field field="displayName" label="표시 이름" error={errors.displayName}>
          <Input
            {...fieldProps("displayName")}
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
            required
            maxLength={100}
          />
        </Field>
        <Field
          field="publicClient"
          label="Client 유형"
          error={errors.publicClient}
        >
          <select
            {...fieldProps("publicClient")}
            className="h-9 w-full rounded-md border px-2 text-sm"
            disabled={clientTypeReadOnly}
            value={publicClient ? "public" : "confidential"}
            onChange={(event) =>
              setPublicClient(event.target.value === "public")
            }
          >
            <option value="public">public · secret 없음</option>
            <option value="confidential">confidential · secret 사용</option>
          </select>
        </Field>
        <p className="text-sm text-slate-600">
          URI를 한 줄에 하나씩 입력해 주세요. 입력한 주소를 정확히 비교하며,
          서버에서 http/https 및 localhost 규칙을 최종 검증합니다.
        </p>
        <Field
          field="redirectUris"
          label="Redirect URI"
          error={errors.redirectUris}
        >
          <textarea
            {...fieldProps("redirectUris")}
            className="min-h-28 w-full rounded-md border p-3 font-mono text-sm"
            value={redirectUris}
            onChange={(event) => setRedirectUris(event.target.value)}
            required
          />
        </Field>
        <Field
          field="postLogoutRedirectUris"
          label="Post-logout Redirect URI"
          error={errors.postLogoutRedirectUris}
        >
          <textarea
            {...fieldProps("postLogoutRedirectUris")}
            className="min-h-24 w-full rounded-md border p-3 font-mono text-sm"
            value={postLogoutRedirectUris}
            onChange={(event) => setPostLogoutRedirectUris(event.target.value)}
          />
        </Field>
        <fieldset
          aria-describedby={errors.scopes ? "oauth-scopes-error" : undefined}
        >
          <legend className="mb-3 text-sm font-medium">Scopes</legend>
          <div className="grid gap-3 sm:grid-cols-2">
            {(Object.keys(oauthScopeCatalog) as OAuthScope[]).map((scope) => (
              <label key={scope} className="flex items-center gap-2 text-sm">
                <input
                  name="scopes"
                  type="checkbox"
                  className="size-4 accent-teal-700"
                  checked={scope === "openid" || scopes.includes(scope)}
                  disabled={scope === "openid"}
                  onChange={(event) =>
                    setScopes((current) =>
                      event.target.checked
                        ? [...current, scope]
                        : current.filter((value) => value !== scope),
                    )
                  }
                />
                {scope} · {oauthScopeCatalog[scope].description}
              </label>
            ))}
          </div>
          {errors.scopes ? (
            <p id="oauth-scopes-error" className="mt-2 text-sm text-red-700">
              {errors.scopes}
            </p>
          ) : null}
        </fieldset>
        {canSetTrust ? (
          <Field field="trust" label="신뢰 정책" error={errors.trust}>
            <select
              {...fieldProps("trust")}
              className="h-9 w-full rounded-md border px-2 text-sm"
              value={trust}
              onChange={(event) =>
                setTrust(event.target.value as OAuthClientInput["trust"])
              }
            >
              <option value="CONSENT_REQUIRED">
                CONSENT_REQUIRED · 사용자 동의 필요
              </option>
              <option value="TRUSTED_FIRST_PARTY">
                TRUSTED_FIRST_PARTY · 자사 신뢰 client
              </option>
            </select>
          </Field>
        ) : (
          <div className="space-y-2">
            <p className="text-sm font-medium">신뢰 정책</p>
            <p className="text-sm text-slate-600">
              CONSENT_REQUIRED · 사용자 동의 필요
            </p>
            {errors.trust ? (
              <p role="alert" className="text-sm text-red-700">
                {errors.trust}
              </p>
            ) : null}
          </div>
        )}
      </fieldset>
      <div className="flex justify-end">
        <Button type="submit" disabled={pending}>
          {pending ? "저장 중" : "저장"}
        </Button>
      </div>
    </form>
  );
}

function Field({
  field,
  label,
  error,
  children,
}: {
  field: Field;
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="space-y-2">
      <Label htmlFor={`oauth-${field}`}>{label}</Label>
      {children}
      {error ? (
        <p id={`oauth-${field}-error`} className="text-sm text-red-700">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function OAuthClientCreate({ companyCode }: { companyCode: string }) {
  const { actor, status } = useAuth();
  let code = "";
  try {
    code = decodeURIComponent(resourceCode(companyCode, "회사 코드"));
  } catch {
    /* rendered below */
  }
  const systemAdmin = actor?.roles.includes("SYSTEM_ADMIN") ?? false;
  if (status === "loading")
    return <p aria-busy="true">관리자 화면을 준비하는 중입니다.</p>;
  if (!code) return <p role="alert">회사 코드가 올바르지 않습니다.</p>;
  if (
    status !== "authenticated" ||
    !(
      systemAdmin ||
      (actor?.roles.includes("COMPANY_ADMIN") &&
        actor.companyCode?.toUpperCase() === code)
    )
  )
    return <p role="alert">이 회사의 OAuth client를 관리할 권한이 없습니다.</p>;
  return (
    <CreateForm
      key={`${code}:${actor?.accountId}`}
      companyCode={code}
      canSetTrust={systemAdmin}
    />
  );
}

function CreateForm({
  companyCode,
  canSetTrust,
}: {
  companyCode: string;
  canSetTrust: boolean;
}) {
  const router = useRouter();
  const operations = useOAuthSecretOperations();
  const active = useRef(true);
  useEffect(() => {
    active.current = true;
    return () => {
      active.current = false;
    };
  }, []);
  async function create(input: OAuthClientInput) {
    const result = await oauthClientApi.create(companyCode, input);
    if (!active.current) return;
    if (!result.client.publicClient && result.oneTimeSecret)
      operations.open({
        clientId: result.client.clientId,
        displayName: result.client.displayName,
        oneTimeSecret: result.oneTimeSecret,
      });
    router.push(
      `/companies/${encodeURIComponent(companyCode)}/oauth-clients/${encodeURIComponent(result.client.clientId)}`,
    );
  }
  return (
    <section aria-labelledby="oauth-create-title" className="mx-auto max-w-3xl">
      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold text-teal-700">{companyCode}</p>
          <h1 id="oauth-create-title" className="mt-2 text-3xl font-semibold">
            OAuth client 생성
          </h1>
          <p className="mt-2 text-sm text-slate-600">
            로그인 연동에 사용할 주소와 공개 정보를 설정합니다.
          </p>
        </div>
        <Link
          className={buttonVariants({ variant: "outline" })}
          href={`/companies/${encodeURIComponent(companyCode)}/oauth-clients`}
        >
          목록
        </Link>
      </div>
      <OAuthClientForm canSetTrust={canSetTrust} onSubmit={create} />
    </section>
  );
}
