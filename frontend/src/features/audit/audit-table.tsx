"use client";

import {usePathname, useRouter, useSearchParams} from "next/navigation";
import {useCallback, useEffect, useMemo, useRef, useState} from "react";
import {Badge} from "@/components/ui/badge";
import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {Label} from "@/components/ui/label";
import {Skeleton} from "@/components/ui/skeleton";
import {Table, TableBody, TableCell, TableHead, TableHeader, TableRow} from "@/components/ui/table";
import type {PageResponse} from "@/features/companies/company-api";
import {isApiProblemError} from "@/lib/api/problem";
import {parseListQuery} from "@/lib/pagination-query";
import {resourceCode} from "@/lib/resource-code";
import {auditApi, type AuditListParams, type AuditLog, type AuditSort} from "./audit-api";

const empty: PageResponse<AuditLog> = {content: [], page: 0, size: 20, totalElements: 0, totalPages: 0};
const sorts: readonly AuditSort[] = ["occurredAt", "action", "success", "actorAccountId", "targetType"];

export function AuditTable({companyCode}: { companyCode: string }) {
  const pathname = usePathname();
  const replace = useRouter().replace;
  const queryString = useSearchParams().toString();
  const base = useMemo(() => parseListQuery<AuditSort>(new URLSearchParams(queryString), {
    defaultSort: "occurredAt",
    sorts
  }), [queryString]);
  const parsed = useMemo(() => {
    const input = new URLSearchParams(queryString);
    const canonical = new URLSearchParams(base.canonical);
    const action = input.get("action")?.trim() ?? "";
    if (action) canonical.set("action", action);
    const rawSuccess = input.get("success");
    const success = rawSuccess === "true" ? true : rawSuccess === "false" ? false : undefined;
    if (success !== undefined) canonical.set("success", String(success));
    const entries = [...canonical.entries()];
    const needsReplace = [...input.entries()].length !== entries.length || entries.some(([key, value]) => input.getAll(key).length !== 1 || input.get(key) !== value);
    return {...base, action, success, canonical, needsReplace};
  }, [base, queryString]);
  const canonicalQuery = parsed.canonical.toString();
  const latestQuery = useRef(canonicalQuery);
  const [result, setResult] = useState(empty);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const requestId = useRef(0);
  let canonicalCode = "";
  try {
    canonicalCode = decodeURIComponent(resourceCode(companyCode, "회사 코드"));
  } catch { /* rendered below */
  }
  const replaceQuery = useCallback((changes: Record<string, string | null>) => {
    const next = new URLSearchParams(latestQuery.current);
    Object.entries(changes).forEach(([key, value]) => {
      if (!value || (key === "page" && value === "0") || (key === "size" && value === "20") || (key === "sort" && value === "occurredAt")) next.delete(key); else next.set(key, value);
    });
    latestQuery.current = next.toString();
    replace(`${pathname}${next.size ? `?${next}` : ""}`, {scroll: false});
  }, [pathname, replace]);
  useEffect(() => {
    latestQuery.current = canonicalQuery;
  }, [canonicalQuery]);
  useEffect(() => {
    if (parsed.needsReplace) replace(`${pathname}${canonicalQuery ? `?${canonicalQuery}` : ""}`, {scroll: false});
  }, [canonicalQuery, parsed.needsReplace, pathname, replace]);
  useEffect(() => {
    if (!canonicalCode || parsed.needsReplace) return;
    const id = ++requestId.current;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (id === requestId.current) {
        setLoading(true);
        setError(null);
      }
    });
    const params: AuditListParams = {
      page: parsed.page,
      size: parsed.size,
      sort: parsed.sort,
      search: parsed.search || undefined,
      action: parsed.action || undefined,
      success: parsed.success
    };
    auditApi.list(canonicalCode, params, controller.signal).then((next) => {
      if (id !== requestId.current) return;
      setResult(next);
      if (next.totalPages > 0 && parsed.page >= next.totalPages) replaceQuery({page: String(next.totalPages - 1)});
    }).catch((cause) => {
      if (id !== requestId.current || (cause instanceof DOMException && cause.name === "AbortError")) return;
      setError(isApiProblemError(cause) ? `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})` : cause instanceof Error ? cause.message : "감사 로그를 불러오지 못했습니다.");
    }).finally(() => {
      if (id === requestId.current) setLoading(false);
    });
    return () => {
      controller.abort();
      if (requestId.current === id) requestId.current += 1;
    };
  }, [canonicalCode, parsed.action, parsed.needsReplace, parsed.page, parsed.search, parsed.size, parsed.sort, parsed.success, reload, replaceQuery]);
  if (!canonicalCode) return <section><h1 className="text-3xl font-semibold">감사 로그</h1><p
      className="mt-6 text-red-700">회사 코드가 올바르지 않습니다.</p></section>;
  return <section aria-labelledby="audit-title" className="mx-auto max-w-7xl"><p
      className="text-xs font-semibold text-teal-700">{canonicalCode}</p><h1 className="mt-2 text-3xl font-semibold"
                                                                             id="audit-title">감사 로그</h1><p
      className="mt-2 text-sm text-slate-600">관리자 변경 이력을 읽기 전용으로 조회합니다.</p>
    <div className="mt-6 rounded-xl border bg-white shadow-sm">
      <form className="grid gap-4 border-b p-4 md:grid-cols-[1fr_12rem_10rem_auto]" onSubmit={(event) => {
        event.preventDefault();
        const data = new FormData(event.currentTarget);
        replaceQuery({
          search: String(data.get("search") ?? "").trim() || null,
          action: String(data.get("action") ?? "").trim() || null,
          page: null
        });
      }}><Field id="audit-search" label="감사 로그 검색"><Input defaultValue={parsed.search} id="audit-search" name="search"
                                                          placeholder="actor, target, trace ID"/></Field><Field
          id="audit-action" label="작업"><Input defaultValue={parsed.action} id="audit-action" name="action"
                                              placeholder="USER_CREATE"/></Field><Select id="audit-success"
                                                                                         label="성공 여부"
                                                                                         value={parsed.success === undefined ? "" : String(parsed.success)}
                                                                                         options={[["", "전체"], ["true", "성공"], ["false", "실패"]]}
                                                                                         onChange={(value) => replaceQuery({
                                                                                           success: value || null,
                                                                                           page: null
                                                                                         })}/><Button
          className="self-end" type="submit">검색</Button></form>
      <div className="grid gap-4 border-b p-4 sm:grid-cols-2"><Select id="audit-sort" label="정렬" value={parsed.sort}
                                                                      options={[["occurredAt", "발생 시각"], ["action", "작업"], ["success", "성공 여부"], ["actorAccountId", "행위자"], ["targetType", "대상"]]}
                                                                      onChange={(value) => replaceQuery({
                                                                        sort: value,
                                                                        page: null
                                                                      })}/><Select id="audit-size" label="페이지 크기"
                                                                                   value={String(parsed.size)}
                                                                                   options={[["20", "20개"], ["50", "50개"], ["100", "100개"]]}
                                                                                   onChange={(value) => replaceQuery({
                                                                                     size: value,
                                                                                     page: null
                                                                                   })}/></div>
      <AuditRows loading={loading} error={error} result={result} onRetry={() => setReload((value) => value + 1)}/>
      <div className="flex items-center justify-between border-t px-4 py-3"><p
          className="text-sm text-slate-500">총 {result.totalElements.toLocaleString()}건
        · {result.totalPages ? result.page + 1 : 0}/{result.totalPages} 페이지</p>
        <div className="flex gap-2"><Button disabled={parsed.page === 0} size="sm" variant="outline"
                                            onClick={() => replaceQuery({page: String(Math.max(0, parsed.page - 1))})}>이전</Button><Button
            disabled={parsed.page + 1 >= result.totalPages} size="sm" variant="outline"
            onClick={() => replaceQuery({page: String(parsed.page + 1)})}>다음</Button></div>
      </div>
    </div>
  </section>;
}

function AuditRows({loading, error, result, onRetry}: {
  loading: boolean;
  error: string | null;
  result: PageResponse<AuditLog>;
  onRetry(): void
}) {
  return <div aria-busy={loading} aria-live="polite" className="min-h-64 overflow-x-auto">{loading ?
      <div className="p-5"><span className="sr-only">감사 로그를 불러오는 중입니다.</span><Skeleton className="h-56 w-full"/>
      </div> : error ? <div className="p-8 text-center"><p className="text-red-700">{error}</p><Button className="mt-3"
                                                                                                       variant="outline"
                                                                                                       onClick={onRetry}>다시
        시도</Button></div> : !result.content.length ? <p className="p-12 text-center text-slate-500">감사 로그가 없습니다.</p> :
          <Table><TableHeader><TableRow><TableHead>발생
            시각</TableHead><TableHead>행위자</TableHead><TableHead>작업</TableHead><TableHead>대상</TableHead><TableHead>결과</TableHead><TableHead>추적
            ID</TableHead><TableHead>상세</TableHead></TableRow></TableHeader><TableBody>{result.content.map((item) =>
              <TableRow key={item.id}><TableCell
                  className="whitespace-nowrap">{new Date(item.occurredAt).toLocaleString("ko-KR")}</TableCell><TableCell>계정 {item.actorAccountId}</TableCell><TableCell
                  className="font-mono">{item.action}</TableCell><TableCell>{item.targetType} #{item.targetId}</TableCell><TableCell><Badge
                  variant={item.success ? "default" : "destructive"}>{item.success ? "성공" : "실패"}</Badge></TableCell><TableCell
                  className="max-w-48 break-all font-mono text-xs">{item.traceId}</TableCell><TableCell>
                <details>
                  <summary className="cursor-pointer">보기</summary>
                  <pre
                      className="mt-2 max-w-80 whitespace-pre-wrap break-all text-xs">{JSON.stringify(item.details, null, 2)}</pre>
                </details>
              </TableCell></TableRow>)}</TableBody></Table>}</div>;
}

function Field({id, label, children}: { id: string; label: string; children: React.ReactNode }) {
  return <div className="space-y-2"><Label htmlFor={id}>{label}</Label>{children}</div>;
}

function Select({id, label, value, options, onChange}: {
  id: string;
  label: string;
  value: string;
  options: string[][];
  onChange(value: string): void
}) {
  return <div className="space-y-2"><Label htmlFor={id}>{label}</Label><select
      className="h-9 w-full rounded-md border bg-white px-2 text-sm" id={id} value={value}
      onChange={(event) => onChange(event.target.value)}>{options.map(([key, text]) => <option key={key}
                                                                                               value={key}>{text}</option>)}</select>
  </div>;
}
