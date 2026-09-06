"use client";

import Link from "next/link";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { positionApi, type Position } from "@/features/positions/position-api";
import { CompanyForm } from "./company-form";
import { companyApi, type Company, type CompanyListParams, type CompanyStatus, type PageResponse } from "./company-api";
import { isApiProblemError } from "@/lib/api/problem";
import { parseListQuery } from "@/lib/pagination-query";

const initialPage: PageResponse<Company> = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
const defaultPositionCodes = [
  "ASSISTANT_MANAGER",
  "DEPUTY_GENERAL_MANAGER",
  "EMPLOYEE",
  "GENERAL_MANAGER",
  "MANAGER",
] as const;

type DefaultPositionCheck =
  | { status: "loading"; company: Company }
  | { status: "success"; company: Company; positions: Position[] }
  | { status: "warning"; company: Company }
  | null;

export function CompanyTable() {
  const router = useRouter();
  const replace = router.replace;
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const queryString = searchParams.toString();
  const parsedQuery = useMemo(() => parseListQuery<CompanyListParams["sort"], CompanyStatus>(new URLSearchParams(queryString), {
    defaultSort: "code",
    sorts: ["code", "name", "status"],
    enumKey: "status",
    enumValues: ["ACTIVE", "INACTIVE"],
  }), [queryString]);
  const { page, size, sort, search: querySearch } = parsedQuery;
  const status = parsedQuery.enumValue;
  const [searchDraft, setSearchDraft] = useState({ source: querySearch, value: querySearch });
  const search = searchDraft.source === querySearch ? searchDraft.value : querySearch;
  const [result, setResult] = useState(initialPage);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reload, setReload] = useState(0);
  const [editing, setEditing] = useState<Company | null | undefined>(undefined);
  const [defaultPositionCheck, setDefaultPositionCheck] = useState<DefaultPositionCheck>(null);
  const requestId = useRef(0);
  const provisioningRequestId = useRef(0);
  const provisioningController = useRef<AbortController | null>(null);
  const provisioningCompanyCode = useRef<string | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      provisioningRequestId.current += 1;
      provisioningCompanyCode.current = null;
      provisioningController.current?.abort();
      provisioningController.current = null;
    };
  }, []);

  const replaceQuery = useCallback((changes: Record<string, string | null>) => {
    const next = new URLSearchParams(parsedQuery.canonical);
    Object.entries(changes).forEach(([key, value]) => {
      if (!value || (key === "page" && value === "0") || (key === "size" && value === "20") || (key === "sort" && value === "code")) next.delete(key);
      else next.set(key, value);
    });
    replace(`${pathname}${next.size ? `?${next}` : ""}`, { scroll: false });
  }, [parsedQuery.canonical, pathname, replace]);

  useEffect(() => {
    if (!parsedQuery.needsReplace) return;
    const canonical = parsedQuery.canonical.toString();
    replace(`${pathname}${canonical ? `?${canonical}` : ""}`, { scroll: false });
  }, [parsedQuery.canonical, parsedQuery.needsReplace, pathname, replace]);

  useEffect(() => {
    if (search === querySearch) return;
    const timeout = window.setTimeout(() => replaceQuery({ search: search.trim() || null, page: null }), 300);
    return () => window.clearTimeout(timeout);
  }, [querySearch, replaceQuery, search]);

  useEffect(() => {
    if (parsedQuery.needsReplace) return;
    const id = ++requestId.current;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (id === requestId.current) {
        setLoading(true);
        setError(null);
      }
    });
    companyApi.list({ page, size, sort, search: querySearch || undefined, status }, controller.signal)
      .then((next) => {
        if (id !== requestId.current) return;
        setResult(next);
        if (next.totalPages > 0 && page >= next.totalPages) replaceQuery({ page: String(next.totalPages - 1) });
      })
      .catch((cause) => {
        if (id !== requestId.current || (cause instanceof DOMException && cause.name === "AbortError")) return;
        setError(isApiProblemError(cause) ? `${cause.detail ?? cause.title} (추적 ID: ${cause.traceId})` : "회사 목록을 불러오지 못했습니다.");
      })
      .finally(() => {
        if (id === requestId.current) setLoading(false);
      });
    return () => controller.abort();
  }, [page, parsedQuery.needsReplace, querySearch, reload, replaceQuery, size, sort, status]);

  function handleSaved(saved: Company) {
    setReload((value) => value + 1);
    if (editing === null) {
      window.dispatchEvent(new Event("auth-study:company-created"));
      const id = ++provisioningRequestId.current;
      const companyCode = saved.code.trim().toUpperCase();
      provisioningCompanyCode.current = companyCode;
      provisioningController.current?.abort();
      const controller = new AbortController();
      provisioningController.current = controller;
      const isLatest = () => mounted.current
        && id === provisioningRequestId.current
        && companyCode === provisioningCompanyCode.current
        && controller === provisioningController.current;
      setDefaultPositionCheck({ status: "loading", company: saved });
      void positionApi.list(companyCode, { page: 0, size: 20, sort: "displayOrder" }, controller.signal)
        .then((positions) => {
          if (!isLatest()) return;
          const actualCodes = positions.content.map((position) => position.code).sort();
          const complete = positions.totalElements === 5
            && actualCodes.length === 5
            && actualCodes.every((code, index) => code === defaultPositionCodes[index]);
          setDefaultPositionCheck(complete
            ? { status: "success", company: saved, positions: positions.content }
            : { status: "warning", company: saved });
        })
        .catch((cause) => {
          if (!isLatest() || (cause instanceof DOMException && cause.name === "AbortError")) return;
          setDefaultPositionCheck({ status: "warning", company: saved });
        })
        .finally(() => {
          if (!isLatest()) return;
          provisioningController.current = null;
        });
    }
  }

  return (
    <section aria-labelledby="company-title" className="mx-auto max-w-7xl">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <p className="text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">System scope</p>
          <h1 className="mt-2 text-3xl font-semibold tracking-tight" id="company-title">회사 관리</h1>
          <p className="mt-2 text-sm text-slate-600">테넌트의 기본 정보와 운영 상태를 관리합니다.</p>
        </div>
        <Button onClick={() => setEditing(null)}>회사 생성</Button>
      </div>

      {defaultPositionCheck?.status === "loading" ? (
        <Alert className="mt-6 border-slate-200 bg-white" aria-live="polite">
          <AlertTitle>기본 직위를 확인하는 중입니다.</AlertTitle>
          <AlertDescription>회사 생성은 완료되었습니다.</AlertDescription>
        </Alert>
      ) : defaultPositionCheck?.status === "success" ? (
        <Alert className="mt-6 border-teal-200 bg-teal-50" aria-live="polite">
          <AlertTitle>기본 직위 5개가 준비되었습니다.</AlertTitle>
          <AlertDescription>
            <span className="mt-2 flex flex-wrap gap-2">{defaultPositionCheck.positions.map((position) => <Badge key={position.code} variant="secondary">{position.name}</Badge>)}</span>
            <Link className={buttonVariants({ className: "mt-3", size: "sm", variant: "outline" })} href={`/companies/${defaultPositionCheck.company.code}/positions`}>직위 관리</Link>
          </AlertDescription>
        </Alert>
      ) : defaultPositionCheck?.status === "warning" ? (
        <Alert className="mt-6 border-amber-300 bg-amber-50" aria-live="polite">
          <AlertTitle>회사는 생성되었지만 기본 직위 5개를 확인하지 못했습니다.</AlertTitle>
          <AlertDescription>
            회사 저장을 다시 실행할 필요는 없습니다. 직위 화면에서 현재 상태를 확인해 주세요.
            <Link className={buttonVariants({ className: "mt-3 block", size: "sm", variant: "outline" })} href={`/companies/${defaultPositionCheck.company.code}/positions`}>직위 관리</Link>
          </AlertDescription>
        </Alert>
      ) : null}

      <div className="mt-6 rounded-xl border border-slate-200 bg-white shadow-sm">
        <div className="grid gap-4 border-b border-slate-200 p-4 sm:grid-cols-[minmax(14rem,1fr)_12rem_12rem_8rem]">
          <div className="space-y-2"><Label htmlFor="company-search">회사 검색</Label><Input id="company-search" onChange={(event) => setSearchDraft({ source: querySearch, value: event.target.value })} placeholder="코드, 회사명 또는 도메인" value={search} /></div>
          <Filter label="상태" value={status ?? ""} onChange={(value) => replaceQuery({ status: value || null, page: null })} options={[["", "전체"], ["ACTIVE", "활성"], ["INACTIVE", "비활성"]]} />
          <Filter label="정렬" value={sort} onChange={(value) => replaceQuery({ sort: value, page: null })} options={[["code", "코드"], ["name", "회사명"], ["status", "상태"]]} />
          <Filter label="페이지 크기" value={String(size)} onChange={(value) => replaceQuery({ size: value, page: null })} options={[["20", "20개"], ["50", "50개"], ["100", "100개"]]} />
        </div>
        <div aria-busy={loading} aria-live="polite" className="min-h-52 overflow-x-auto">
          {loading ? <div className="space-y-3 p-5"><span className="sr-only">회사 목록을 불러오는 중입니다.</span>{[1, 2, 3].map((item) => <Skeleton className="h-10 w-full" key={item} />)}</div>
            : error ? <div className="p-6 text-center"><p className="text-sm text-red-700">{error}</p><Button className="mt-3" onClick={() => setReload((value) => value + 1)} variant="outline">다시 시도</Button></div>
            : result.content.length === 0 ? <p className="p-12 text-center text-sm text-slate-500">등록된 회사가 없습니다.</p>
            : <Table><TableHeader><TableRow><TableHead>코드</TableHead><TableHead>회사명</TableHead><TableHead>이메일 도메인</TableHead><TableHead>상태</TableHead><TableHead className="text-right">작업</TableHead></TableRow></TableHeader><TableBody>{result.content.map((company) => <TableRow key={company.code}><TableCell className="font-mono font-medium">{company.code}</TableCell><TableCell>{company.name}</TableCell><TableCell>{company.emailDomain}</TableCell><TableCell><Badge variant={company.status === "ACTIVE" ? "default" : "secondary"}>{company.status === "ACTIVE" ? "활성" : "비활성"}</Badge></TableCell><TableCell className="space-x-2 text-right"><Button onClick={() => setEditing(company)} size="sm" variant="outline">{company.code} 수정</Button><Link className={buttonVariants({ size: "sm", variant: "ghost" })} href={`/companies/${company.code}/positions`}>직위</Link></TableCell></TableRow>)}</TableBody></Table>}
        </div>
        <Pagination page={result.page} totalPages={result.totalPages} totalElements={result.totalElements} onPage={(next) => replaceQuery({ page: String(next) })} />
      </div>
      {editing !== undefined ? <CompanyForm company={editing} onOpenChange={(open) => !open && setEditing(undefined)} onSaved={handleSaved} open /> : null}
    </section>
  );
}

function Filter({ label, value, onChange, options }: { label: string; value: string; onChange(value: string): void; options: string[][] }) {
  const id = `company-${label}`;
  return <div className="space-y-2"><Label htmlFor={id}>{label}</Label><select className="h-9 w-full rounded-md border border-slate-300 bg-white px-3 text-sm" id={id} onChange={(event) => onChange(event.target.value)} value={value}>{options.map(([key, text]) => <option key={key} value={key}>{text}</option>)}</select></div>;
}

function Pagination({ page, totalPages, totalElements, onPage }: { page: number; totalPages: number; totalElements: number; onPage(page: number): void }) {
  const last = Math.max(0, totalPages - 1);
  return <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 px-4 py-3"><p className="text-sm text-slate-500">총 {totalElements.toLocaleString()}개 · {totalPages === 0 ? 0 : page + 1}/{totalPages} 페이지</p><div className="flex gap-1"><Button disabled={page === 0 || totalPages === 0} onClick={() => onPage(0)} size="sm" variant="outline">처음</Button><Button disabled={page === 0 || totalPages === 0} onClick={() => onPage(page - 1)} size="sm" variant="outline">이전</Button><Button disabled={page >= last || totalPages === 0} onClick={() => onPage(page + 1)} size="sm" variant="outline">다음</Button><Button disabled={page >= last || totalPages === 0} onClick={() => onPage(last)} size="sm" variant="outline">마지막</Button></div></div>;
}
