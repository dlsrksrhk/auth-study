"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState } from "react";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useAuth } from "@/features/auth/auth-provider";
import { isApiProblemError } from "@/lib/api/problem";
import {
  oauthClientApi,
  type OAuthClientDetail as Client,
  type OAuthClientInput,
} from "./oauth-client-api";
import { OAuthClientForm } from "./oauth-client-form";
import { useOAuthSecretOperations } from "./oauth-secret-operation-provider";

type Props = { companyCode: string; clientId: string };
type Action = "rotateSecret" | "revokeSecret" | "disable" | "enable";
const actions: Record<Action, { label: string; description: string }> = {
  rotateSecret: {
    label: "secret 회전",
    description:
      "기존 secret과 활성 authorization과 refresh를 폐기합니다. 새 secret은 한 번만 표시되며 연동 서비스에 새 값을 적용해야 합니다.",
  },
  revokeSecret: {
    label: "secret 폐기",
    description:
      "기존 secret과 활성 authorization과 refresh를 폐기합니다. 새 secret은 발급하지 않으므로 secret 인증을 다시 사용하려면 회전으로 발급해야 합니다.",
  },
  disable: {
    label: "비활성화",
    description:
      "이 client의 활성 authorization과 refresh가 폐기되며 새로운 로그인이 중단됩니다. 다시 활성화해도 폐기된 권한은 복원되지 않습니다.",
  },
  enable: {
    label: "활성화",
    description:
      "이 client의 새로운 로그인을 허용합니다. 폐기된 authorization과 refresh는 복원되지 않습니다. Confidential client는 유효한 secret도 필요합니다.",
  },
};

export function OAuthClientDetail({ companyCode, clientId }: Props) {
  const { actor, status } = useAuth();
  const canSetTrust = actor?.roles.includes("SYSTEM_ADMIN") ?? false;
  // Next has already resolved route params. Keep opaque IDs unchanged for API encoding.
  const validCompany = /^[A-Za-z0-9][A-Za-z0-9_-]{0,49}$/.test(companyCode);
  if (status === "loading")
    return <p aria-busy="true">관리자 화면을 준비하는 중입니다.</p>;
  if (!validCompany || !clientId)
    return <p role="alert">회사 코드 또는 client_id가 올바르지 않습니다.</p>;
  if (
    status !== "authenticated" ||
    !(
      canSetTrust ||
      (actor?.roles.includes("COMPANY_ADMIN") &&
        actor.companyCode?.toUpperCase() === companyCode.toUpperCase())
    )
  )
    return <p role="alert">이 회사의 OAuth client를 관리할 권한이 없습니다.</p>;
  return (
    <ClientDetail
      key={`${companyCode}:${clientId}:${actor?.accountId}:${canSetTrust}`}
      companyCode={companyCode}
      clientId={clientId}
      canSetTrust={canSetTrust}
    />
  );
}

function ClientDetail({
  companyCode,
  clientId,
  canSetTrust,
}: Props & { canSetTrust: boolean }) {
  const operations = useOAuthSecretOperations();
  const [client, setClient] = useState<Client | null>(null);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [conflict, setConflict] = useState(false);
  const [message, setMessage] = useState("");
  const [action, setAction] = useState<Action | null>(null);
  const active = useRef(false);
  const submitting = useRef(false);
  const request = useRef<AbortController | null>(null);

  function showError(cause: unknown) {
    setConflict(isApiProblemError(cause) && cause.status === 409);
    setError(
      isApiProblemError(cause)
        ? `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})`
        : "요청을 처리하지 못했습니다. 다시 시도해 주세요.",
    );
  }

  const load = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    setError(null);
    setConflict(false);
    try {
      const next = await oauthClientApi.get(
        companyCode,
        clientId,
        controller.signal,
      );
      if (active.current && !controller.signal.aborted) setClient(next);
    } catch (cause) {
      if (active.current && !controller.signal.aborted) {
        setClient(null);
        showError(cause);
      }
    } finally {
      if (active.current && !controller.signal.aborted) setLoading(false);
    }
  }, [companyCode, clientId]);

  useEffect(() => {
    active.current = true;
    queueMicrotask(() => {
      if (active.current) void load();
    });
    return () => {
      active.current = false;
      request.current?.abort();
    };
  }, [load]);

  async function update(input: OAuthClientInput) {
    if (!client || submitting.current || conflict) return;
    submitting.current = true;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      await oauthClientApi.update(companyCode, clientId, {
        displayName: input.displayName,
        redirectUris: input.redirectUris,
        postLogoutRedirectUris: input.postLogoutRedirectUris,
        scopes: input.scopes,
        trust: input.trust,
        version: client.version,
      });
      if (active.current) {
        setMessage("설정을 저장했습니다.");
        await load();
      }
    } catch (cause) {
      if (active.current) {
        showError(cause);
        throw cause;
      }
    } finally {
      submitting.current = false;
      if (active.current) setPending(false);
    }
  }

  async function runAction() {
    if (!action || !client || submitting.current || conflict) return;
    submitting.current = true;
    setPending(true);
    setError(null);
    setMessage("");
    try {
      if (action === "rotateSecret") {
        const result = await oauthClientApi.rotateSecret(companyCode, clientId);
        if (!active.current) return;
        // Only the dedicated memory-only provider receives the raw secret.
        if (!result.client.publicClient && result.oneTimeSecret)
          operations.open({
            clientId: result.client.clientId,
            displayName: result.client.displayName,
            oneTimeSecret: result.oneTimeSecret,
          });
      } else if (action === "revokeSecret") {
        await oauthClientApi.revokeSecret(companyCode, clientId);
      } else {
        await oauthClientApi[action](companyCode, clientId, client.version);
      }
      if (!active.current) return;
      setAction(null);
      setMessage(`${actions[action].label} 처리가 완료되었습니다.`);
      // No shared query cache exists: refresh detail here; list fetches on route entry.
      await load();
    } catch (cause) {
      if (active.current) {
        setAction(null);
        showError(cause);
      }
    } finally {
      submitting.current = false;
      if (active.current) setPending(false);
    }
  }

  async function copyId() {
    try {
      await navigator.clipboard.writeText(clientId);
      if (active.current) setMessage("client_id를 복사했습니다.");
    } catch {
      if (active.current) setMessage("client_id를 직접 선택해 복사해 주세요.");
    }
  }

  const trustedReadOnly =
    !canSetTrust && client?.trust === "TRUSTED_FIRST_PARTY";
  const availableActions: Action[] =
    client?.status === "ACTIVE"
      ? client.publicClient
        ? ["disable"]
        : ["rotateSecret", "revokeSecret", "disable"]
      : ["enable"];
  return (
    <section
      aria-labelledby="oauth-detail-title"
      className="mx-auto max-w-3xl space-y-5"
    >
      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold text-teal-700">{companyCode}</p>
          <h1 id="oauth-detail-title" className="mt-2 text-3xl font-semibold">
            {client?.displayName ?? "OAuth client 상세"}
          </h1>
        </div>
        <Link
          className={buttonVariants({ variant: "outline" })}
          href={`/companies/${encodeURIComponent(companyCode)}/oauth-clients`}
        >
          목록
        </Link>
      </div>
      <p aria-live="polite" role="status">
        {message}
      </p>
      {error ? (
        <div className="rounded-md border border-red-200 p-3 text-sm text-red-700">
          <p role="alert">{error}</p>
          {conflict ? (
            <p>
              최신 상세 정보를 다시 불러온 뒤 변경 내용을 확인해 주세요. 다시
              불러오면 저장하지 않은 편집 내용이 사라집니다.
            </p>
          ) : null}
          <Button
            variant="outline"
            disabled={pending || loading}
            onClick={() => void load()}
          >
            최신 정보 불러오기
          </Button>
        </div>
      ) : null}
      {loading ? (
        <p aria-busy="true">OAuth client 상세를 불러오는 중입니다.</p>
      ) : client ? (
        <>
          <div className="space-y-2 rounded-xl border bg-white p-5">
            <Label htmlFor="oauth-client-id">client_id</Label>
            <div className="flex gap-2">
              <Input
                id="oauth-client-id"
                readOnly
                value={clientId}
                className="font-mono"
              />
              <Button variant="outline" onClick={() => void copyId()}>
                client_id 복사
              </Button>
            </div>
            <p className="text-sm">
              상태: {client.status} ·{" "}
              {client.publicClient ? "public" : "confidential"}
            </p>
            {!client.publicClient ? (
              <p className="text-sm">
                활성 secret 힌트: {client.activeSecretHint ?? "없음"}
              </p>
            ) : null}
          </div>
          <fieldset
            disabled={pending || conflict}
            className="space-y-4"
            aria-busy={pending}
          >
            <legend className="text-lg font-semibold">설정</legend>
            {trustedReadOnly ? (
              <div className="space-y-3 rounded-xl border bg-white p-5 text-sm">
                <p>
                  이 신뢰 client의 설정은 시스템 관리자만 수정할 수 있습니다.
                  상태와 secret은 아래에서 관리할 수 있습니다.
                </p>
                <dl className="space-y-2">
                  <dt>신뢰 정책</dt>
                  <dd>{client.trust}</dd>
                  <dt>Redirect URI</dt>
                  <dd className="whitespace-pre-wrap break-all font-mono">
                    {client.redirectUris.join("\n")}
                  </dd>
                  <dt>Post-logout Redirect URI</dt>
                  <dd className="whitespace-pre-wrap break-all font-mono">
                    {client.postLogoutRedirectUris.join("\n") || "없음"}
                  </dd>
                  <dt>Scopes</dt>
                  <dd>{client.scopes.join(", ")}</dd>
                </dl>
              </div>
            ) : (
              <OAuthClientForm
                key={client.version}
                initialValues={client}
                canSetTrust={canSetTrust}
                clientTypeReadOnly
                onSubmit={update}
              />
            )}
            <div className="flex flex-wrap gap-2">
              {availableActions.map((item) => (
                <Button
                  key={item}
                  variant="outline"
                  type="button"
                  onClick={() => setAction(item)}
                >
                  {actions[item].label}
                </Button>
              ))}
            </div>
          </fieldset>
        </>
      ) : null}
      <Dialog
        open={action !== null}
        onOpenChange={(next) => {
          if (!next && !pending) setAction(null);
        }}
      >
        <DialogContent showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>
              {action ? actions[action].label : "작업"} 확인
            </DialogTitle>
            <DialogDescription>
              {action ? actions[action].description : ""}
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button
              variant="outline"
              disabled={pending}
              onClick={() => setAction(null)}
            >
              취소
            </Button>
            <Button disabled={pending} onClick={() => void runAction()}>
              {pending ? "처리 중" : "확인"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
