"use client";

import Link from "next/link";
import {useRouter} from "next/navigation";
import {LogOut} from "lucide-react";

import {Button} from "@/components/ui/button";
import {useAuth} from "@/features/auth/auth-provider";

const segmentLabels: Record<string, string> = {
  companies: "회사",
  positions: "직위",
  departments: "부서",
  users: "사용자",
  "audit-logs": "감사 로그",
};

export function AdminHeader({pathname, companyControl}: { pathname: string; companyControl: React.ReactNode }) {
  const {actor, logout} = useAuth();
  const router = useRouter();
  const segments = pathname.split("/").filter(Boolean);

  return (
      <header className="border-b border-slate-200 bg-white px-4 py-3 sm:px-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <nav aria-label="현재 위치">
            <ol className="flex items-center gap-2 text-sm text-slate-500">
              <li><Link className="hover:text-teal-700" href="/">대시보드</Link></li>
              {segments.map((segment, index) => (
                  <li className="flex items-center gap-2" key={`${segment}-${index}`}>
                    <span aria-hidden="true">/</span>
                    <span className={index === segments.length - 1 ? "font-medium text-slate-900" : ""}>
                  {segmentLabels[segment] ?? segment.toUpperCase()}
                </span>
                  </li>
              ))}
            </ol>
          </nav>
          <div className="flex flex-wrap items-center justify-end gap-3">
            {companyControl}
            <span className="hidden text-sm text-slate-600 lg:inline">{actor?.email}</span>
            <Button
                aria-label="로그아웃"
                onClick={async () => {
                  await logout();
                  router.replace("/login");
                }}
                size="icon-sm"
                variant="ghost"
            >
              <LogOut aria-hidden="true"/>
            </Button>
          </div>
        </div>
      </header>
  );
}
