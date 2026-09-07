"use client";

import Link from "next/link";
import {
  Building2,
  ChartNoAxesCombined,
  FileClock,
  IdCard,
  Network,
  ShieldKeyhole,
  UserRound,
} from "lucide-react";

import type { Role } from "@/features/auth/auth-api";
import { cn } from "@/lib/utils";

type AdminSidebarProps = {
  roles: Role[];
  companyCode: string | null;
  currentPath: string;
};

export function AdminSidebar({ roles, companyCode, currentPath }: AdminSidebarProps) {
  const systemAdmin = roles.includes("SYSTEM_ADMIN");
  const companyAdmin = roles.includes("COMPANY_ADMIN");
  const code = companyCode?.trim().toUpperCase() || null;
  const companyBase = code ? `/companies/${code}` : "/companies";
  const items = systemAdmin || companyAdmin
    ? [
        { label: "대시보드", href: systemAdmin && code ? `/?companyCode=${code}` : "/", icon: ChartNoAxesCombined },
        ...(systemAdmin ? [{ label: "회사", href: "/companies", icon: Building2 }] : []),
        { label: "직위", href: code ? `${companyBase}/positions` : companyBase, icon: IdCard },
        { label: "부서", href: code ? `${companyBase}/departments` : companyBase, icon: Network },
        { label: "사용자", href: code ? `${companyBase}/users` : companyBase, icon: UserRound },
        { label: "감사 로그", href: code ? `${companyBase}/audit-logs` : companyBase, icon: FileClock },
        { label: "인증/인가 설정", href: code ? `${companyBase}/oauth-clients` : companyBase, icon: ShieldKeyhole },
      ]
    : [{ label: "내 계정", href: "/account", icon: UserRound }];

  return (
    <aside className="border-b border-slate-800 bg-slate-950 text-slate-200 md:min-h-screen md:w-64 md:shrink-0 md:border-r md:border-b-0">
      <div className="px-4 py-5 md:px-6 md:py-7">
        <p className="text-xs font-semibold uppercase tracking-[0.2em] text-teal-400">Auth Study</p>
        <p className="mt-1 text-lg font-semibold text-white">HR Admin</p>
      </div>
      <nav aria-label="관리자 메뉴" className="overflow-x-auto px-2 pb-3 md:px-3">
        <ul className="flex min-w-max gap-1 md:min-w-0 md:flex-col">
          {items.map(({ label, href, icon: Icon }) => {
            const active = href === "/"
              ? currentPath === "/"
              : currentPath === href.split("?")[0] || currentPath.startsWith(`${href.split("?")[0]}/`);
            return (
              <li key={label}>
                <Link
                  aria-current={active ? "page" : undefined}
                  className={cn(
                    "flex items-center gap-2 rounded-md px-3 py-2 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-teal-400",
                    active ? "bg-teal-600 text-white" : "text-slate-300 hover:bg-slate-800 hover:text-white",
                  )}
                  href={href}
                >
                  <Icon aria-hidden="true" className="size-4" />
                  {label}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
      {systemAdmin && !code ? (
        <p className="px-4 pb-4 text-xs text-slate-300">인증/인가 설정을 관리하려면 먼저 회사를 선택해 주세요.</p>
      ) : null}
    </aside>
  );
}
