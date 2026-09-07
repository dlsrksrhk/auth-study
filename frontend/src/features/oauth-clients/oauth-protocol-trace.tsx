"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { Button, buttonVariants } from "@/components/ui/button";
import { useAuth } from "@/features/auth/auth-provider";
import {
  oauthClientApi,
  type CursorResponse,
  type ProtocolEvent,
  type ProtocolEventType,
  type ProtocolEventOutcome,
} from "./oauth-client-api";
import { oauthScopeCatalog } from "./oauth-scope-catalog";

type Props = { companyCode: string; clientId: string };
const eventLabels = {
  AUTHORIZATION_REQUEST_VALIDATED: "인가 요청 검증",
  LOGIN_REQUIRED: "로그인 필요",
  LOGIN_SUCCEEDED: "로그인 성공",
  LOGIN_FAILED: "로그인 실패",
  PASSWORD_CHANGE_REQUIRED: "비밀번호 변경 필요",
  PASSWORD_CHANGED: "비밀번호 변경",
  CONSENT_GRANTED: "동의 승인",
  CONSENT_DENIED: "동의 거부",
  AUTHORIZATION_CODE_ISSUED: "인가 코드 발급",
  AUTHORIZATION_CODE_EXCHANGED: "인가 코드 교환",
  AUTHORIZATION_CODE_REPLAY_REJECTED: "인가 코드 재사용 거부",
  REFRESH_ROTATED: "Refresh 회전",
  REFRESH_REUSE_DETECTED: "Refresh 재사용 탐지",
  AUTHORIZATION_REVOKED: "인가 폐기",
  USERINFO_SUCCEEDED: "사용자 정보 조회 성공",
  USERINFO_DENIED: "사용자 정보 조회 거부",
  LOGOUT_COMPLETED: "로그아웃 완료",
} satisfies Record<ProtocolEventType, string>;
const outcomeLabels = {
  SUCCESS: "성공",
  FAILURE: "실패",
  DENIED: "거부",
} satisfies Record<ProtocolEventOutcome, string>;
// Match the backend's typed metadata contract, including values. Never stringify arbitrary data.
const metadataEnums: Record<string, readonly string[]> = {
  endpoint: [
    "AUTHORIZE",
    "LOGIN",
    "PASSWORD",
    "CONSENT",
    "TOKEN",
    "REVOCATION",
    "USERINFO",
    "LOGOUT",
  ],
  grant_type: ["AUTHORIZATION_CODE", "REFRESH_TOKEN"],
  response_type: ["CODE"],
  authentication_method: ["NONE", "CLIENT_SECRET_BASIC", "CLIENT_SECRET_POST"],
  reason: [
    "INVALID_REQUEST",
    "INVALID_CLIENT",
    "INVALID_GRANT",
    "INVALID_SCOPE",
    "INVALID_TOKEN",
    "ACCESS_DENIED",
    "LOGIN_FAILED",
    "SESSION_INVALID",
    "TOKEN_REUSE",
    "CODE_REPLAY",
    "STATE_INVALID",
    "SERVER_ERROR",
  ],
};
function safeMetadata(key: string, value: unknown): string | null {
  if (Object.hasOwn(metadataEnums, key))
    return typeof value === "string" && metadataEnums[key].includes(value)
      ? value
      : null;
  if (
    ["public_client", "redirect_validated", "session_invalidated"].includes(key)
  )
    return typeof value === "boolean" ? String(value) : null;
  if (key === "http_status")
    return typeof value === "number" &&
      Number.isInteger(value) &&
      value >= 100 &&
      value <= 599
      ? String(value)
      : null;
  if (
    key === "scopes" &&
    Array.isArray(value) &&
    value.every(
      (scope) =>
        typeof scope === "string" && Object.hasOwn(oauthScopeCatalog, scope),
    )
  )
    return value.join(", ");
  return null;
}
function Metadata({ value }: { value: Record<string, unknown> }) {
  const entries =
    value && typeof value === "object" && !Array.isArray(value)
      ? Object.entries(value)
      : [["", null] as const];
  return (
    <dl className="flex flex-wrap gap-3 text-sm">
      {entries.map(([key, item], index) => {
        const safe = safeMetadata(key, item);
        return safe === null ? (
          <div key={index}>
            <dt className="sr-only">숨긴 metadata</dt>
            <dd className="rounded bg-slate-100 px-2">redacted</dd>
          </div>
        ) : (
          <div key={index}>
            <dt className="text-slate-500">{key}</dt>
            <dd>{safe}</dd>
          </div>
        );
      })}
    </dl>
  );
}
export function OAuthProtocolTrace({ companyCode, clientId }: Props) {
  const { actor, status } = useAuth();
  const search = useSearchParams();
  const system = actor?.roles.includes("SYSTEM_ADMIN");
  if (status === "loading")
    return <p aria-busy="true">이벤트 화면을 준비하는 중입니다.</p>;
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
    return <p role="alert">이 회사의 OAuth 이벤트를 조회할 권한이 없습니다.</p>;
  const rawType = search.get("type") ?? "";
  const rawOutcome = search.get("outcome") ?? "";
  const type = Object.hasOwn(eventLabels, rawType)
    ? (rawType as ProtocolEventType)
    : undefined;
  const outcome = Object.hasOwn(outcomeLabels, rawOutcome)
    ? (rawOutcome as ProtocolEventOutcome)
    : undefined;
  const cursor = search.get("cursor") || undefined;
  return (
    <Trace
      key={`${companyCode}:${clientId}:${actor?.accountId}:${system}:${type}:${outcome}:${cursor}`}
      companyCode={companyCode}
      clientId={clientId}
      type={type}
      outcome={outcome}
      cursor={cursor}
    />
  );
}
function Trace({
  companyCode,
  clientId,
  type,
  outcome,
  cursor,
}: Props & {
  type?: ProtocolEventType;
  outcome?: ProtocolEventOutcome;
  cursor?: string;
}) {
  const router = useRouter();
  const [data, setData] = useState<CursorResponse<ProtocolEvent> | null>(null);
  const [error, setError] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const path = `/companies/${encodeURIComponent(companyCode)}/oauth-clients/${encodeURIComponent(clientId)}`;
  useEffect(() => {
    const controller = new AbortController();
    oauthClientApi
      .listProtocolEvents(
        companyCode,
        clientId,
        { size: 50, cursor, type, outcome },
        controller.signal,
      )
      .then((next) => {
        if (!controller.signal.aborted) setData(next);
      })
      .catch(() => {
        if (!controller.signal.aborted) setError(true);
      });
    return () => controller.abort();
  }, [companyCode, clientId, cursor, type, outcome, attempt]);
  function navigate(
    nextType: ProtocolEventType | undefined,
    nextOutcome: ProtocolEventOutcome | undefined,
    nextCursor?: string,
  ) {
    const query = new URLSearchParams();
    if (nextType) query.set("type", nextType);
    if (nextOutcome) query.set("outcome", nextOutcome);
    if (nextCursor) query.set("cursor", nextCursor);
    router.push(`${path}/protocol-events${query.size ? `?${query}` : ""}`, {
      scroll: false,
    });
  }
  return (
    <section
      aria-labelledby="oauth-trace-title"
      className="mx-auto max-w-4xl space-y-5"
    >
      <div className="flex items-center justify-between gap-4">
        <h1 id="oauth-trace-title" className="text-3xl font-semibold">
          Protocol 이벤트
        </h1>
        <Link className={buttonVariants({ variant: "outline" })} href={path}>
          client 상세
        </Link>
      </div>
      <div className="flex flex-wrap gap-4">
        <label htmlFor="oauth-event-type">
          이벤트 유형
          <select
            id="oauth-event-type"
            className="ml-2 rounded border p-2"
            value={type ?? ""}
            onChange={(e) =>
              navigate(
                e.target.value
                  ? (e.target.value as ProtocolEventType)
                  : undefined,
                outcome,
              )
            }
          >
            <option value="">전체</option>
            {Object.entries(eventLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label} ({value})
              </option>
            ))}
          </select>
        </label>
        <label htmlFor="oauth-event-outcome">
          결과
          <select
            id="oauth-event-outcome"
            className="ml-2 rounded border p-2"
            value={outcome ?? ""}
            onChange={(e) =>
              navigate(
                type,
                e.target.value
                  ? (e.target.value as ProtocolEventOutcome)
                  : undefined,
              )
            }
          >
            <option value="">전체</option>
            {Object.entries(outcomeLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      </div>
      {error ? (
        <div>
          <p role="alert">
            Protocol 이벤트를 불러오지 못했습니다. 권한 또는 조회 조건을 확인해
            주세요.
          </p>
          <Button
            variant="outline"
            onClick={() => {
              setError(false);
              setData(null);
              setAttempt(attempt + 1);
            }}
          >
            이벤트 다시 시도
          </Button>
        </div>
      ) : !data ? (
        <p aria-busy="true">Protocol 이벤트를 불러오는 중입니다.</p>
      ) : (
        <>
          {data.content.length ? (
            <ol className="space-y-3">
              {data.content.map((event) => (
                <li
                  key={event.id}
                  className="space-y-2 rounded-xl border bg-white p-5"
                >
                  <p>
                    <time dateTime={event.occurredAt}>{event.occurredAt}</time>
                  </p>
                  <p>
                    {Object.hasOwn(eventLabels, event.eventType)
                      ? `${eventLabels[event.eventType]} (${event.eventType})`
                      : "redacted"}{" "}
                    ·{" "}
                    {Object.hasOwn(outcomeLabels, event.outcome)
                      ? outcomeLabels[event.outcome]
                      : "redacted"}
                  </p>
                  <dl className="text-sm">
                    <dt>correlationId</dt>
                    <dd className="break-all font-mono">
                      {event.correlationId}
                    </dd>
                    <dt>subject</dt>
                    <dd>
                      {event.subject
                        ? `${event.subject.slice(0, 8)}…${event.subject.slice(-4)}`
                        : "없음"}
                    </dd>
                    <dt>errorCode</dt>
                    <dd>{event.errorCode ?? "없음"}</dd>
                  </dl>
                  <Metadata value={event.metadata} />
                </li>
              ))}
            </ol>
          ) : (
            <p>조건에 맞는 protocol 이벤트가 없습니다.</p>
          )}
        </>
      )}
      <nav aria-label="이벤트 페이지" className="flex gap-3">
        <Button
          variant="outline"
          disabled={!cursor}
          onClick={() => navigate(type, outcome)}
        >
          첫 이벤트
        </Button>
        <Button
          variant="outline"
          disabled={!data?.hasNext || !data.nextCursor || error}
          onClick={() => navigate(type, outcome, data?.nextCursor ?? undefined)}
        >
          다음 이벤트
        </Button>
      </nav>
    </section>
  );
}
