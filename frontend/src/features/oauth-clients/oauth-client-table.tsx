"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { useAuth } from "@/features/auth/auth-provider";
import type { PageResponse } from "@/features/companies/company-api";
import { isApiProblemError } from "@/lib/api/problem";
import { parseListQuery } from "@/lib/pagination-query";
import { resourceCode } from "@/lib/resource-code";
import { oauthClientApi, type OAuthClientSummary } from "./oauth-client-api";

export function OAuthClientTable({ companyCode }: { companyCode: string }) {
  const { actor, status } = useAuth();
  let code = "";
  try {
    code = decodeURIComponent(resourceCode(companyCode, "회사 코드"));
  } catch {
    /* rendered below */
  }
  if (status === "loading")
    return <p aria-busy="true">관리자 화면을 준비하는 중입니다.</p>;
  if (!code) return <p role="alert">회사 코드가 올바르지 않습니다.</p>;
  if (
    status !== "authenticated" ||
    !(
      actor?.roles.includes("SYSTEM_ADMIN") ||
      (actor?.roles.includes("COMPANY_ADMIN") &&
        actor.companyCode?.toUpperCase() === code)
    )
  )
    return <p role="alert">이 회사의 OAuth client를 관리할 권한이 없습니다.</p>;
  return <ClientList key={`${code}:${actor?.accountId}`} companyCode={code} />;
}

function ClientList({ companyCode }: { companyCode: string }) {
  const { replace } = useRouter();
  const pathname = usePathname();
  const queryString = useSearchParams().toString();
  const parsed = useMemo(
    () =>
      parseListQuery(new URLSearchParams(queryString), {
        defaultSort: "",
        sorts: [""],
      }),
    [queryString],
  );
  const { page, size, needsReplace } = parsed;
  const [result, setResult] = useState<PageResponse<OAuthClientSummary> | null>(
    null,
  );
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const base = `/companies/${encodeURIComponent(companyCode)}/oauth-clients`;

  function changePage(nextPage: number, nextSize = size) {
    const query = new URLSearchParams();
    if (nextPage) query.set("page", String(nextPage));
    if (nextSize !== 20) query.set("size", String(nextSize));
    replace(`${pathname}${query.size ? `?${query}` : ""}`, { scroll: false });
  }

  useEffect(() => {
    if (needsReplace)
      replace(
        `${pathname}${parsed.canonical.size ? `?${parsed.canonical}` : ""}`,
        { scroll: false },
      );
  }, [needsReplace, parsed.canonical, pathname, replace]);

  useEffect(() => {
    if (needsReplace) return;
    let active = true;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (active) {
        setLoading(true);
        setError(null);
      }
    });
    oauthClientApi
      .list(companyCode, { page, size }, controller.signal)
      .then((next) => {
        if (!active) return;
        setResult(next);
        if (page > 0 && page >= next.totalPages) {
          const query = new URLSearchParams();
          if (next.totalPages > 1)
            query.set("page", String(next.totalPages - 1));
          if (size !== 20) query.set("size", String(size));
          replace(`${pathname}${query.size ? `?${query}` : ""}`, {
            scroll: false,
          });
        }
      })
      .catch((cause) => {
        if (!active) return;
        setError(
          isApiProblemError(cause)
            ? `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})`
            : "OAuth client 목록을 불러오지 못했습니다.",
        );
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
      controller.abort();
    };
  }, [companyCode, page, size, needsReplace, reload, replace, pathname]);

  return (
    <section aria-labelledby="oauth-list-title" className="mx-auto max-w-7xl">
      <div className="flex items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold text-teal-700">{companyCode}</p>
          <h1 id="oauth-list-title" className="mt-2 text-3xl font-semibold">
            OAuth clients
          </h1>
          <p className="mt-2 text-sm text-slate-600">
            로그인 연동 client와 허용된 공개 정보를 관리합니다.
          </p>
        </div>
        <Link className={buttonVariants()} href={`${base}/new`}>
          OAuth client 생성
        </Link>
      </div>
      <div className="mt-6 rounded-xl border bg-white shadow-sm">
        <div className="flex items-center justify-end gap-3 border-b p-4">
          <Label htmlFor="oauth-page-size">페이지 크기</Label>
          <select
            id="oauth-page-size"
            className="h-9 rounded-md border px-2 text-sm"
            value={size}
            onChange={(event) => changePage(0, Number(event.target.value))}
          >
            {[20, 50, 100].map((value) => (
              <option value={value} key={value}>
                {value}개
              </option>
            ))}
          </select>
        </div>
        <div
          aria-busy={loading}
          aria-live="polite"
          className="min-h-52 overflow-x-auto"
        >
          {loading ? (
            <div className="p-5">
              <span className="sr-only">
                OAuth client 목록을 불러오는 중입니다.
              </span>
              <Skeleton className="h-40 w-full" />
            </div>
          ) : error ? (
            <div className="p-8 text-center">
              <p role="alert" className="text-sm text-red-700">
                {error}
              </p>
              <Button
                variant="outline"
                className="mt-3"
                onClick={() => setReload((value) => value + 1)}
              >
                다시 시도
              </Button>
            </div>
          ) : !result?.content.length ? (
            <p className="p-12 text-center text-sm text-slate-500">
              등록된 OAuth client가 없습니다.
            </p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  {[
                    "표시 이름",
                    "client_id",
                    "유형",
                    "상태",
                    "신뢰 정책",
                    "Scopes",
                    "수정 일시",
                    "상세",
                  ].map((label) => (
                    <TableHead key={label}>{label}</TableHead>
                  ))}
                </TableRow>
              </TableHeader>
              <TableBody>
                {result.content.map((item) => (
                  <TableRow key={item.clientId}>
                    <TableCell>{item.displayName}</TableCell>
                    <TableCell className="font-mono">{item.clientId}</TableCell>
                    <TableCell>
                      {item.publicClient ? "public" : "confidential"}
                    </TableCell>
                    <TableCell>
                      <Badge>{item.status}</Badge>
                    </TableCell>
                    <TableCell>{item.trust}</TableCell>
                    <TableCell>{item.scopes.join(", ")}</TableCell>
                    <TableCell>
                      <time dateTime={item.updatedAt}>
                        {new Date(item.updatedAt).toLocaleString("ko-KR")}
                      </time>
                    </TableCell>
                    <TableCell>
                      <Link
                        aria-label={`${item.displayName} 상세`}
                        className={buttonVariants({
                          variant: "outline",
                          size: "sm",
                        })}
                        href={`${base}/${encodeURIComponent(item.clientId)}`}
                      >
                        상세
                      </Link>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </div>
        <div className="flex items-center justify-between border-t px-4 py-3">
          <p className="text-sm text-slate-500">
            총 {result?.totalElements ?? 0}개 ·{" "}
            {result?.totalPages ? result.page + 1 : 0}/{result?.totalPages ?? 0}{" "}
            페이지
          </p>
          <div className="flex gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={loading || Boolean(error) || page === 0}
              onClick={() => changePage(page - 1)}
            >
              이전
            </Button>
            <Button
              size="sm"
              variant="outline"
              disabled={
                loading ||
                Boolean(error) ||
                page + 1 >= (result?.totalPages ?? 0)
              }
              onClick={() => changePage(page + 1)}
            >
              다음
            </Button>
          </div>
        </div>
      </div>
    </section>
  );
}
