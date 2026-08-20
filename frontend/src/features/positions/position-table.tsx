"use client";

import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import type { PageResponse } from "@/features/companies/company-api";
import { isApiProblemError } from "@/lib/api/problem";
import { PositionForm } from "./position-form";
import { positionApi, type Position, type PositionListParams } from "./position-api";

const emptyPage: PageResponse<Position> = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

export function PositionTable({ companyCode }: { companyCode: string }) {
  const canonicalCode = companyCode.trim().toUpperCase();
  const router = useRouter();
  const replace = router.replace;
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const queryString = searchParams.toString();
  const page = Math.max(0, Number(searchParams.get("page") ?? 0) || 0);
  const size = Math.min(100, Math.max(1, Number(searchParams.get("size") ?? 20) || 20));
  const sort = (["code", "name", "level", "displayOrder"].includes(searchParams.get("sort") ?? "") ? searchParams.get("sort") : "displayOrder") as PositionListParams["sort"];
  const querySearch = searchParams.get("search") ?? "";
  const activeParam = searchParams.get("active");
  const active = activeParam === "true" ? true : activeParam === "false" ? false : undefined;
  const [searchDraft, setSearchDraft] = useState({ source: querySearch, value: querySearch });
  const search = searchDraft.source === querySearch ? searchDraft.value : querySearch;
  const [result, setResult] = useState(emptyPage);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [editing, setEditing] = useState<Position | null | undefined>(undefined);
  const requestId = useRef(0);

  const replaceQuery = useCallback((changes: Record<string, string | null>) => {
    const next = new URLSearchParams(queryString);
    Object.entries(changes).forEach(([key, value]) => {
      if (!value || (key === "page" && value === "0") || (key === "size" && value === "20") || (key === "sort" && value === "displayOrder")) next.delete(key);
      else next.set(key, value);
    });
    replace(`${pathname}${next.size ? `?${next}` : ""}`, { scroll: false });
  }, [pathname, queryString, replace]);

  useEffect(() => {
    if (search === querySearch) return;
    const timeout = window.setTimeout(() => replaceQuery({ search: search.trim() || null, page: null }), 300);
    return () => window.clearTimeout(timeout);
  }, [querySearch, replaceQuery, search]);

  useEffect(() => {
    const id = ++requestId.current;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (id === requestId.current) {
        setLoading(true);
        setError(null);
      }
    });
    positionApi.list(canonicalCode, { page, size, sort, search: querySearch || undefined, active }, controller.signal)
      .then((next) => {
        if (id !== requestId.current) return;
        setResult(next);
        if (next.totalPages > 0 && page >= next.totalPages) replaceQuery({ page: String(next.totalPages - 1) });
      })
      .catch((cause) => {
        if (id !== requestId.current || (cause instanceof DOMException && cause.name === "AbortError")) return;
        setError(isApiProblemError(cause) ? `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})` : "직위 목록을 불러오지 못했습니다.");
      })
      .finally(() => id === requestId.current && setLoading(false));
    return () => controller.abort();
  }, [active, canonicalCode, page, querySearch, reload, replaceQuery, size, sort]);

  function handleSaved(saved: Position) {
    setResult((current) => ({
      ...current,
      content: current.content.some((item) => item.code === saved.code)
        ? current.content.map((item) => item.code === saved.code ? saved : item)
        : [...current.content, saved],
      totalElements: current.content.some((item) => item.code === saved.code) ? current.totalElements : current.totalElements + 1,
    }));
  }

  return (
    <section aria-labelledby="position-title" className="mx-auto max-w-7xl">
      <div className="flex flex-wrap items-end justify-between gap-4"><div><p className="text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">{canonicalCode}</p><h1 className="mt-2 text-3xl font-semibold tracking-tight" id="position-title">직위 관리</h1><p className="mt-2 text-sm text-slate-600">직위 단계와 화면 표시 순서를 관리합니다.</p></div><Button onClick={() => setEditing(null)}>직위 생성</Button></div>
      <div className="mt-6 rounded-xl border border-slate-200 bg-white shadow-sm">
        <div className="grid gap-4 border-b border-slate-200 p-4 sm:grid-cols-[minmax(14rem,1fr)_12rem_12rem_8rem]">
          <div className="space-y-2"><Label htmlFor="position-search">직위 검색</Label><Input id="position-search" onChange={(event) => setSearchDraft({ source: querySearch, value: event.target.value })} placeholder="코드 또는 직위명" value={search} /></div>
          <SelectFilter id="position-active" label="상태" onChange={(value) => replaceQuery({ active: value || null, page: null })} options={[["", "전체"], ["true", "활성"], ["false", "비활성"]]} value={activeParam ?? ""} />
          <SelectFilter id="position-sort" label="정렬" onChange={(value) => replaceQuery({ sort: value, page: null })} options={[["displayOrder", "표시 순서"], ["code", "코드"], ["name", "직위명"], ["level", "레벨"]]} value={sort} />
          <SelectFilter id="position-size" label="페이지 크기" onChange={(value) => replaceQuery({ size: value, page: null })} options={[["20", "20개"], ["50", "50개"], ["100", "100개"]]} value={String(size)} />
        </div>
        <div aria-busy={loading} aria-live="polite" className="min-h-52 overflow-x-auto">
          {loading ? <div className="space-y-3 p-5"><span className="sr-only">직위 목록을 불러오는 중입니다.</span>{[1, 2, 3].map((item) => <Skeleton className="h-10 w-full" key={item} />)}</div>
            : error ? <div className="p-6 text-center"><p className="text-sm text-red-700">{error}</p><Button className="mt-3" onClick={() => setReload((value) => value + 1)} variant="outline">다시 시도</Button></div>
            : result.content.length === 0 ? <p className="p-12 text-center text-sm text-slate-500">등록된 직위가 없습니다.</p>
            : <Table><TableHeader><TableRow><TableHead>코드</TableHead><TableHead>직위명</TableHead><TableHead className="text-right">레벨</TableHead><TableHead className="text-right">표시 순서</TableHead><TableHead>상태</TableHead><TableHead className="text-right">작업</TableHead></TableRow></TableHeader><TableBody>{result.content.map((position) => <TableRow key={position.code}><TableCell className="font-mono font-medium">{position.code}</TableCell><TableCell>{position.name}</TableCell><TableCell className="text-right tabular-nums">{position.level}</TableCell><TableCell className="text-right tabular-nums">{position.displayOrder}</TableCell><TableCell><Badge variant={position.active ? "default" : "secondary"}>{position.active ? "활성" : "비활성"}</Badge></TableCell><TableCell className="text-right"><Button aria-label={`${position.code} 수정`} onClick={() => setEditing(position)} size="sm" variant="outline">수정</Button></TableCell></TableRow>)}</TableBody></Table>}
        </div>
        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 px-4 py-3"><p className="text-sm text-slate-500">총 {result.totalElements.toLocaleString()}개 · {result.totalPages === 0 ? 0 : result.page + 1}/{result.totalPages} 페이지</p><div className="flex gap-1"><Button disabled={page === 0 || result.totalPages === 0} onClick={() => replaceQuery({ page: null })} size="sm" variant="outline">처음</Button><Button disabled={page === 0 || result.totalPages === 0} onClick={() => replaceQuery({ page: String(page - 1) })} size="sm" variant="outline">이전</Button><Button disabled={page + 1 >= result.totalPages} onClick={() => replaceQuery({ page: String(page + 1) })} size="sm" variant="outline">다음</Button><Button disabled={page + 1 >= result.totalPages} onClick={() => replaceQuery({ page: String(Math.max(0, result.totalPages - 1)) })} size="sm" variant="outline">마지막</Button></div></div>
      </div>
      {editing !== undefined ? <PositionForm companyCode={canonicalCode} onOpenChange={(open) => !open && setEditing(undefined)} onReload={() => setReload((value) => value + 1)} onSaved={handleSaved} open position={editing} /> : null}
    </section>
  );
}

function SelectFilter({ id, label, value, onChange, options }: { id: string; label: string; value: string; onChange(value: string): void; options: string[][] }) {
  return <div className="space-y-2"><Label htmlFor={id}>{label}</Label><select className="h-9 w-full rounded-md border border-slate-300 bg-white px-3 text-sm" id={id} onChange={(event) => onChange(event.target.value)} value={value}>{options.map(([key, text]) => <option key={key} value={key}>{text}</option>)}</select></div>;
}
