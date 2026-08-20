"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { PageResponse } from "@/features/companies/company-api";
import { positionApi, type Position } from "@/features/positions/position-api";
import { isApiProblemError } from "@/lib/api/problem";
import { parseListQuery } from "@/lib/pagination-query";
import { resourceCode } from "@/lib/resource-code";
import { UserForm } from "./user-form";
import { userApi, type User, type UserListParams, type UserStatus } from "./user-api";

const empty: PageResponse<User> = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

export function UserTable({ companyCode }: { companyCode: string }) {
  const router = useRouter(); const replace = router.replace; const pathname = usePathname(); const searchParams = useSearchParams(); const queryString = searchParams.toString();
  const parsed = useMemo(() => parseListQuery<UserListParams["sort"], UserStatus>(new URLSearchParams(queryString), { defaultSort: "code", sorts: ["code", "employeeNumber", "name", "status"], enumKey: "status", enumValues: ["PENDING", "ACTIVE", "LOCKED", "RESIGNED"] }), [queryString]);
  const { page, size, sort, search: querySearch, enumValue: status } = parsed;
  const [searchDraft, setSearchDraft] = useState({ source: querySearch, value: querySearch }); const search = searchDraft.source === querySearch ? searchDraft.value : querySearch;
  const [result, setResult] = useState(empty); const [positions, setPositions] = useState<Position[]>([]); const [loading, setLoading] = useState(true); const [error, setError] = useState<string | null>(null); const [reload, setReload] = useState(0); const [creating, setCreating] = useState(false); const requestId = useRef(0);
  let canonicalCode = ""; try { canonicalCode = decodeURIComponent(resourceCode(companyCode, "회사 코드")); } catch { /* rendered below */ }
  const replaceQuery = useCallback((changes: Record<string, string | null>) => { const next = new URLSearchParams(parsed.canonical); Object.entries(changes).forEach(([key, value]) => { if (!value || (key === "page" && value === "0") || (key === "size" && value === "20") || (key === "sort" && value === "code")) next.delete(key); else next.set(key, value); }); replace(`${pathname}${next.size ? `?${next}` : ""}`, { scroll: false }); }, [parsed.canonical, pathname, replace]);
  useEffect(() => { if (!parsed.needsReplace) return; const query = parsed.canonical.toString(); replace(`${pathname}${query ? `?${query}` : ""}`, { scroll: false }); }, [parsed.canonical, parsed.needsReplace, pathname, replace]);
  useEffect(() => { if (search === querySearch) return; const timeout = window.setTimeout(() => replaceQuery({ search: search.trim() || null, page: null }), 300); return () => clearTimeout(timeout); }, [querySearch, replaceQuery, search]);
  useEffect(() => {
    if (parsed.needsReplace || !canonicalCode) return;
    const id = ++requestId.current; const controller = new AbortController(); queueMicrotask(() => { if (id === requestId.current) { setLoading(true); setError(null); } });
    Promise.all([userApi.list(canonicalCode, { page, size, sort, search: querySearch || undefined, status }, controller.signal), positionApi.list(canonicalCode, { page: 0, size: 100, sort: "displayOrder", active: true }, controller.signal)]).then(([next, nextPositions]) => { if (id !== requestId.current) return; setResult(next); setPositions(nextPositions.content); if (next.totalPages > 0 && page >= next.totalPages) replaceQuery({ page: String(next.totalPages - 1) }); }).catch((cause) => { if (id !== requestId.current || (cause instanceof DOMException && cause.name === "AbortError")) return; setError(isApiProblemError(cause) ? `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})` : cause instanceof Error ? cause.message : "사용자 목록을 불러오지 못했습니다."); }).finally(() => { if (id === requestId.current) setLoading(false); });
    return () => { controller.abort(); if (requestId.current === id) requestId.current += 1; };
  }, [canonicalCode, page, parsed.needsReplace, querySearch, reload, replaceQuery, size, sort, status]);
  if (!canonicalCode) return <section aria-labelledby="user-title"><h1 id="user-title" className="text-3xl font-semibold">사용자 관리</h1><p aria-live="assertive" className="mt-6 rounded-xl border bg-white p-8 text-red-700">회사 코드가 올바르지 않습니다.</p></section>;
  return <section aria-labelledby="user-title" className="mx-auto max-w-7xl"><div className="flex items-end justify-between gap-4"><div><p className="text-xs font-semibold text-teal-700">{canonicalCode}</p><h1 className="mt-2 text-3xl font-semibold" id="user-title">사용자 관리</h1><p className="mt-2 text-sm text-slate-600">이름, 코드, 사번 검색과 사용자 생명주기를 관리합니다.</p></div><Button onClick={() => setCreating(true)}>사용자 생성</Button></div>
    <div className="mt-6 rounded-xl border bg-white shadow-sm"><div className="grid gap-4 border-b p-4 sm:grid-cols-[1fr_12rem_12rem_8rem]"><div className="space-y-2"><Label htmlFor="user-search">사용자 검색</Label><Input id="user-search" placeholder="이름, 코드 또는 사번" value={search} onChange={(event) => setSearchDraft({ source: querySearch, value: event.target.value })} /></div><Filter id="user-status" label="상태" value={status ?? ""} options={[["", "전체"], ["PENDING", "대기"], ["ACTIVE", "활성"], ["LOCKED", "잠금"], ["RESIGNED", "퇴사"]]} onChange={(value) => replaceQuery({ status: value || null, page: null })} /><Filter id="user-sort" label="정렬" value={sort} options={[["code", "코드"], ["employeeNumber", "사번"], ["name", "이름"], ["status", "상태"]]} onChange={(value) => replaceQuery({ sort: value, page: null })} /><Filter id="user-size" label="페이지 크기" value={String(size)} options={[["20", "20개"], ["50", "50개"], ["100", "100개"]]} onChange={(value) => replaceQuery({ size: value, page: null })} /></div>
      <div aria-busy={loading} aria-live="polite" className="min-h-52 overflow-x-auto">{loading ? <div className="space-y-3 p-5"><span className="sr-only">사용자 목록을 불러오는 중입니다.</span><Skeleton className="h-40 w-full" /></div> : error ? <div className="p-8 text-center"><p className="text-sm text-red-700">{error}</p><Button className="mt-3" variant="outline" onClick={() => setReload((value) => value + 1)}>다시 시도</Button></div> : !result.content.length ? <p className="p-12 text-center text-sm text-slate-500">등록된 사용자가 없습니다.</p> : <Table><TableHeader><TableRow><TableHead>코드</TableHead><TableHead>사번</TableHead><TableHead>이름</TableHead><TableHead>이메일</TableHead><TableHead>상태</TableHead><TableHead>상세</TableHead></TableRow></TableHeader><TableBody>{result.content.map((item) => <TableRow key={item.id}><TableCell className="font-mono">{item.code}</TableCell><TableCell>{item.employeeNumber}</TableCell><TableCell>{item.name}</TableCell><TableCell>{item.loginEmail}</TableCell><TableCell><Badge>{item.status}</Badge></TableCell><TableCell><Button render={<Link href={`/companies/${encodeURIComponent(canonicalCode)}/users/${encodeURIComponent(item.code)}`} />} size="sm" variant="outline">상세</Button></TableCell></TableRow>)}</TableBody></Table>}</div>
      <div className="flex items-center justify-between border-t px-4 py-3"><p className="text-sm text-slate-500">총 {result.totalElements}명 · {result.totalPages ? result.page + 1 : 0}/{result.totalPages} 페이지</p><div className="flex gap-2"><Button disabled={page === 0} size="sm" variant="outline" onClick={() => replaceQuery({ page: String(Math.max(0, page - 1)) })}>이전</Button><Button disabled={page + 1 >= result.totalPages} size="sm" variant="outline" onClick={() => replaceQuery({ page: String(page + 1) })}>다음</Button></div></div></div>
    {creating ? <UserForm companyCode={canonicalCode} onOpenChange={setCreating} onRefresh={() => setReload((value) => value + 1)} open positions={positions} /> : null}
  </section>;
}
function Filter({ id, label, value, options, onChange }: { id: string; label: string; value: string; options: string[][]; onChange(value: string): void }) { return <div className="space-y-2"><Label htmlFor={id}>{label}</Label><select id={id} className="h-9 w-full rounded-md border px-2 text-sm" value={value} onChange={(event) => onChange(event.target.value)}>{options.map(([key, text]) => <option key={key} value={key}>{text}</option>)}</select></div>; }
