"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { useAuth } from "@/features/auth/auth-provider";
import { oauthClientApi, type ConsentSummary } from "./oauth-client-api";
import { oauthScopeCatalog } from "./oauth-scope-catalog";
import type { PageResponse } from "@/features/companies/company-api";

type Props = { companyCode: string; clientId: string };

export function OAuthConsentList({ companyCode, clientId }: Props) {
  const { actor, status } = useAuth();
  const system = actor?.roles.includes("SYSTEM_ADMIN");
  if (status === "loading")
    return <p aria-busy="true">동의 화면을 준비하는 중입니다.</p>;
  if (
    !/^[A-Za-z0-9][A-Za-z0-9_-]{0,49}$/.test(companyCode) ||
    !clientId ||
    status !== "authenticated" ||
    !(
      system ||
      (actor?.roles.includes("COMPANY_ADMIN") &&
        actor.companyCode?.toUpperCase() === companyCode.toUpperCase())
    )
  )
    return <p role="alert">이 회사의 OAuth 동의를 관리할 권한이 없습니다.</p>;
  return (
    <ConsentList
      key={`${companyCode}:${clientId}:${actor?.accountId}:${system}`}
      companyCode={companyCode}
      clientId={clientId}
    />
  );
}

function ConsentList({ companyCode, clientId }: Props) {
  const [data, setData] = useState<PageResponse<ConsentSummary> | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [action, setAction] = useState<{ subject: string } | "all" | null>(
    null,
  );
  const active = useRef(false);
  const submitting = useRef(false);
  const request = useRef<AbortController | null>(null);
  const load = useCallback(async () => {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setLoading(true);
    setError("");
    try {
      const next = await oauthClientApi.listConsents(
        companyCode,
        clientId,
        { page, size: 20 },
        controller.signal,
      );
      if (!active.current || controller.signal.aborted) return;
      if (!next.content.length && page > 0) {
        setPage(page - 1);
        return;
      }
      setData(next);
    } catch {
      if (active.current && !controller.signal.aborted) {
        setData(null);
        setError("동의 목록을 불러오지 못했습니다. 다시 시도해 주세요.");
      }
    } finally {
      if (active.current && !controller.signal.aborted) setLoading(false);
    }
  }, [companyCode, clientId, page]);
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
  async function revoke() {
    if (!action || submitting.current) return;
    submitting.current = true;
    setPending(true);
    setError("");
    setMessage("");
    try {
      if (action === "all")
        await oauthClientApi.revokeAuthorizations(companyCode, clientId);
      else
        await oauthClientApi.revokeConsent(
          companyCode,
          clientId,
          action.subject,
        );
      if (!active.current) return;
      setAction(null);
      setMessage("폐기 처리가 완료되었습니다.");
      await load();
    } catch {
      if (active.current) {
        setAction(null);
        setError(
          "폐기하지 못했습니다. 권한과 연결 상태를 확인하고 다시 시도해 주세요.",
        );
      }
    } finally {
      submitting.current = false;
      if (active.current) setPending(false);
    }
  }
  async function copy(subject: string) {
    try {
      await navigator.clipboard.writeText(subject);
      if (active.current) setMessage("subject를 복사했습니다.");
    } catch {
      if (active.current)
        setMessage(
          "subject를 복사하지 못했습니다. 브라우저의 클립보드 권한을 확인해 주세요.",
        );
    }
  }
  return (
    <section
      aria-labelledby="oauth-consent-title"
      className="space-y-4 rounded-xl border bg-white p-5"
    >
      <h2 id="oauth-consent-title" className="text-xl font-semibold">
        사용자 동의
      </h2>
      <p className="text-sm">
        subject는 이 client에서 사용하는 익명 식별자입니다. 전체 값은 복사로
        확인할 수 있습니다.
      </p>
      <p aria-live="polite">{message}</p>
      {error ? <p role="alert">{error}</p> : null}
      <div className="flex flex-wrap gap-2">
        <Button
          variant="outline"
          disabled={loading || pending}
          onClick={() => void load()}
        >
          동의 새로고침
        </Button>
        <Button
          variant="outline"
          disabled={loading || pending || !data}
          onClick={() => setAction("all")}
        >
          전체 로그인 유지 권한 폐기
        </Button>
      </div>
      {loading ? (
        <p aria-busy="true">동의 목록을 불러오는 중입니다.</p>
      ) : data ? (
        <>
          {data.content.length ? (
            <ul className="space-y-3">
              {data.content.map((consent) => (
                <li
                  key={consent.subject}
                  className="space-y-2 rounded-md border p-3"
                >
                  <p className="font-mono">
                    {consent.subject.slice(0, 8)}…{consent.subject.slice(-4)}
                  </p>
                  <ul>
                    {consent.approvedScopes.map((scope) => {
                      const entry = Object.hasOwn(oauthScopeCatalog, scope)
                        ? oauthScopeCatalog[scope]
                        : null;
                      return entry ? (
                        <li key={scope}>
                          {scope} · {entry.label}
                          <span className="sr-only">: {entry.description}</span>
                        </li>
                      ) : (
                        <li key={scope}>알 수 없는 scope</li>
                      );
                    })}
                  </ul>
                  <p className="text-sm">
                    승인: {consent.grantedAt} · 변경: {consent.updatedAt}
                  </p>
                  <div className="flex gap-2">
                    <Button
                      variant="outline"
                      onClick={() => void copy(consent.subject)}
                    >
                      subject 복사
                    </Button>
                    <Button
                      variant="outline"
                      disabled={pending}
                      onClick={() => setAction({ subject: consent.subject })}
                    >
                      동의 폐기
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          ) : (
            <p>승인된 동의가 없습니다.</p>
          )}
          <nav aria-label="동의 페이지" className="flex items-center gap-3">
            <Button
              variant="outline"
              disabled={pending || page === 0}
              onClick={() => setPage(page - 1)}
            >
              이전 동의
            </Button>
            <span>
              {page + 1} / {Math.max(1, data.totalPages)}
            </span>
            <Button
              variant="outline"
              disabled={pending || page + 1 >= data.totalPages}
              onClick={() => setPage(page + 1)}
            >
              다음 동의
            </Button>
          </nav>
        </>
      ) : null}
      <Dialog
        open={action !== null}
        onOpenChange={(open) => {
          if (!open && !pending) setAction(null);
        }}
      >
        <DialogContent showCloseButton={false}>
          <DialogHeader>
            <DialogTitle>
              {action === "all"
                ? "전체 로그인 유지 권한 폐기 확인"
                : "동의 폐기 확인"}
            </DialogTitle>
            <DialogDescription>
              {action === "all"
                ? "이 client의 모든 사용자의 로그인 유지 권한과 refresh를 폐기합니다. 승인된 동의는 유지됩니다. 이후 로그인 시 기존 동의를 다시 사용할 수 있습니다."
                : "해당 사용자의 해당 client 동의와 로그인 유지 권한, refresh를 폐기합니다. 이후 로그인 시 동의가 다시 필요합니다."}
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
            <Button
              aria-label={action === "all" ? "전체 로그인 유지 권한 폐기 확인" : "동의 폐기 확인"}
              disabled={pending}
              onClick={() => void revoke()}
            >
              {pending ? "처리 중" : "확인"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </section>
  );
}
