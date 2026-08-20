"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import { companyApi, type Company } from "@/features/companies/company-api";

type Props = {
  pathname: string;
  selectedCompanyCode: string | null;
  fixedCompanyCode?: string | null;
};

export function CompanySwitcher({ pathname, selectedCompanyCode, fixedCompanyCode }: Props) {
  const router = useRouter();
  const [companies, setCompanies] = useState<Company[]>([]);
  const [error, setError] = useState(false);

  useEffect(() => {
    if (fixedCompanyCode !== undefined) return;
    const controller = new AbortController();
    companyApi.list({ page: 0, size: 100, sort: "code" }, controller.signal)
      .then((page) => setCompanies(page.content.filter((company) => company.status === "ACTIVE")))
      .catch((cause) => {
        if (!(cause instanceof DOMException && cause.name === "AbortError")) setError(true);
      });
    return () => controller.abort();
  }, [fixedCompanyCode]);

  if (fixedCompanyCode !== undefined) {
    return <span className="rounded-md bg-slate-100 px-3 py-2 text-sm font-medium text-slate-700">{fixedCompanyCode}</span>;
  }

  return (
    <div>
      <label className="sr-only" htmlFor="company-switcher">관리 회사</label>
      <select
        className="h-9 max-w-52 rounded-md border border-slate-300 bg-white px-3 text-sm"
        id="company-switcher"
        onChange={(event) => {
          const next = event.target.value;
          if (!next) return router.replace("/");
          const match = pathname.match(/^\/companies\/[^/]+(\/.*)$/);
          router.replace(match ? `/companies/${next}${match[1]}` : `/?companyCode=${next}`);
        }}
        value={selectedCompanyCode ?? ""}
      >
        <option value="">회사 선택</option>
        {companies.map((company) => <option key={company.code} value={company.code}>{company.name} ({company.code})</option>)}
      </select>
      {error ? <p aria-live="polite" className="mt-1 text-xs text-red-700">회사 목록을 불러오지 못했습니다.</p> : null}
    </div>
  );
}
