"use client";

import {useEffect, useMemo, useRef, useState} from "react";
import {useRouter} from "next/navigation";

import {Button} from "@/components/ui/button";
import {Input} from "@/components/ui/input";
import {companyApi, type Company, type PageResponse} from "@/features/companies/company-api";

type Props = {
  pathname: string;
  selectedCompanyCode: string | null;
  fixedCompanyCode?: string | null;
};

const emptyPage: PageResponse<Company> = {
  content: [], page: 0, size: 20, totalElements: 0, totalPages: 0,
};

export function CompanySwitcher({pathname, selectedCompanyCode, fixedCompanyCode}: Props) {
  const router = useRouter();
  const [search, setSearch] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const [page, setPage] = useState(0);
  const [result, setResult] = useState(emptyPage);
  const [selectedDetail, setSelectedDetail] = useState<{ code: string; company: Company } | null>(null);
  const [reload, setReload] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(false);
  const listRequestId = useRef(0);
  const selectedCode = selectedCompanyCode?.trim().toUpperCase() || null;

  useEffect(() => {
    if (fixedCompanyCode !== undefined || search === debouncedSearch) return;
    const timeout = window.setTimeout(() => {
      setDebouncedSearch(search.trim());
      setPage(0);
    }, 300);
    return () => window.clearTimeout(timeout);
  }, [debouncedSearch, fixedCompanyCode, search]);

  useEffect(() => {
    if (fixedCompanyCode !== undefined) return;
    const id = ++listRequestId.current;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (id === listRequestId.current) {
        setLoading(true);
        setError(false);
      }
    });
    companyApi.list({
      page,
      size: 20,
      sort: "code",
      search: debouncedSearch || undefined,
    }, controller.signal)
        .then((next) => {
          if (id === listRequestId.current) setResult(next);
        })
        .catch((cause) => {
          if (id === listRequestId.current && !(cause instanceof DOMException && cause.name === "AbortError")) setError(true);
        })
        .finally(() => {
          if (id === listRequestId.current) setLoading(false);
        });
    return () => controller.abort();
  }, [debouncedSearch, fixedCompanyCode, page, pathname, reload, selectedCode]);

  useEffect(() => {
    if (fixedCompanyCode !== undefined || !selectedCode) return;
    const controller = new AbortController();
    companyApi.find(selectedCode, controller.signal)
        .then((company) => setSelectedDetail({code: selectedCode, company}))
        .catch((cause) => {
          if (!(cause instanceof DOMException && cause.name === "AbortError")) setSelectedDetail(null);
        });
    return () => controller.abort();
  }, [fixedCompanyCode, reload, selectedCode]);

  useEffect(() => {
    if (fixedCompanyCode !== undefined) return;
    const invalidate = () => {
      setPage(0);
      setReload((value) => value + 1);
    };
    window.addEventListener("auth-study:company-created", invalidate);
    return () => window.removeEventListener("auth-study:company-created", invalidate);
  }, [fixedCompanyCode]);

  const options = useMemo(() => {
    const merged = new Map(result.content.map((company) => [company.code, company]));
    if (selectedDetail?.code === selectedCode) merged.set(selectedCode, selectedDetail.company);
    return [...merged.values()];
  }, [result.content, selectedCode, selectedDetail]);

  if (fixedCompanyCode !== undefined) {
    return <span
        className="rounded-md bg-slate-100 px-3 py-2 text-sm font-medium text-slate-700">{fixedCompanyCode}</span>;
  }

  return (
      <div className="w-full max-w-sm rounded-lg border border-slate-200 bg-white p-2 shadow-sm sm:w-auto">
        <label className="sr-only" htmlFor="company-switcher-search">회사 선택 검색</label>
        <Input className="mb-2 h-8" id="company-switcher-search" onChange={(event) => setSearch(event.target.value)}
               placeholder="회사 검색" value={search}/>
        <label className="sr-only" htmlFor="company-switcher">관리 회사</label>
        <select
            aria-busy={loading}
            className="h-9 w-full rounded-md border border-slate-300 bg-white px-3 text-sm"
            id="company-switcher"
            onChange={(event) => {
              const next = event.target.value;
              if (!next) return router.replace("/");
              const match = pathname.match(/^\/companies\/[^/]+(\/.*)$/);
              router.replace(match ? `/companies/${next}${match[1]}` : `/?companyCode=${next}`);
            }}
            value={selectedCode ?? ""}
        >
          <option value="">회사 선택</option>
          {selectedCode && !options.some((company) => company.code === selectedCode) ?
              <option value={selectedCode}>{selectedCode} · 확인 중</option> : null}
          {options.map((company) => <option key={company.code}
                                            value={company.code}>{company.name} ({company.code}){company.status === "INACTIVE" ? " · 비활성" : ""}</option>)}
        </select>
        <div className="mt-2 flex items-center justify-between gap-2">
          <Button aria-label="이전 회사 페이지" disabled={page === 0 || loading}
                  onClick={() => setPage((value) => Math.max(0, value - 1))} size="sm" variant="ghost">이전</Button>
          <span
              className="text-xs text-slate-500">{result.totalPages === 0 ? 0 : result.page + 1} / {result.totalPages} 페이지</span>
          <Button aria-label="다음 회사 페이지" disabled={page + 1 >= result.totalPages || loading}
                  onClick={() => setPage((value) => value + 1)} size="sm" variant="ghost">다음</Button>
        </div>
        {error ? <p aria-live="polite" className="mt-1 text-xs text-red-700">회사 목록을 불러오지 못했습니다.</p> : null}
      </div>
  );
}
