"use client";

import { Suspense, useEffect } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";

import { AdminHeader } from "@/components/layout/admin-header";
import { resolveAdminCompanyCode, resolveAdminRedirect } from "@/components/layout/admin-access";
import { AdminSidebar } from "@/components/layout/admin-sidebar";
import { CompanySwitcher } from "@/components/layout/company-switcher";
import { Skeleton } from "@/components/ui/skeleton";
import { useAuth } from "@/features/auth/auth-provider";

export default function AdminLayout({ children }: { children: React.ReactNode }) {
  return (
    <Suspense fallback={<AdminLoading />}>
      <AdminLayoutContent>{children}</AdminLayoutContent>
    </Suspense>
  );
}

function AdminLayoutContent({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const router = useRouter();
  const pathCompany = pathname.match(/^\/companies\/([^/]+)/)?.[1]?.toUpperCase() ?? null;
  const systemSelection = pathCompany ?? searchParams.get("companyCode")?.toUpperCase() ?? null;
  const actorCompany = auth.actor?.companyCode?.toUpperCase() ?? null;
  const companyScope = resolveAdminCompanyCode({
    roles: auth.actor?.roles ?? [],
    actorCompanyCode: actorCompany,
    urlCompanyCode: systemSelection,
  });
  const companyCode = companyScope.companyCode;
  const redirect = resolveAdminRedirect({
    status: auth.status,
    roles: auth.actor?.roles ?? [],
    companyCode: actorCompany,
    pathname,
  });

  useEffect(() => {
    if (redirect) router.replace(redirect);
  }, [redirect, router]);

  if (auth.status === "loading" || redirect || !auth.actor) {
    return <AdminLoading />;
  }

  return (
    <div className="min-h-screen bg-slate-100 md:flex">
      <AdminSidebar companyCode={companyCode} currentPath={pathname} roles={auth.actor.roles} />
      <div className="min-w-0 flex-1">
        <AdminHeader
          companyControl={
            <CompanySwitcher
              fixedCompanyCode={companyScope.fixed ? actorCompany : undefined}
              pathname={pathname}
              selectedCompanyCode={companyCode}
            />
          }
          pathname={pathname}
        />
        <main className="p-4 sm:p-6 lg:p-8">{children}</main>
      </div>
    </div>
  );
}

function AdminLoading() {
  return (
    <main aria-busy="true" className="min-h-screen bg-slate-100 p-8">
      <span className="sr-only">관리자 화면을 준비하는 중입니다.</span>
      <Skeleton className="h-16 w-full" />
      <Skeleton className="mt-5 h-80 w-full" />
    </main>
  );
}
