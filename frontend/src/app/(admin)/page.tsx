import { Building2, ShieldCheck, UsersRound } from "lucide-react";

const cards = [
  ["회사 범위", "선택한 회사의 조직 정보를 안전하게 관리합니다.", Building2],
  ["역할 분리", "시스템·회사 관리자 권한을 명확히 구분합니다.", ShieldCheck],
  ["조직 운영", "직위, 부서와 사용자를 한 흐름에서 관리합니다.", UsersRound],
] as const;

export default function AdminHomePage() {
  return (
    <section aria-labelledby="dashboard-title" className="mx-auto max-w-6xl">
      <p className="text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">Workspace</p>
      <h1 className="mt-2 text-3xl font-semibold tracking-tight text-slate-950" id="dashboard-title">관리자 대시보드</h1>
      <p className="mt-2 text-sm text-slate-600">회사를 선택하고 HR 기준 정보를 관리해 주세요.</p>
      <div className="mt-8 grid gap-4 md:grid-cols-3">
        {cards.map(([title, description, Icon]) => (
          <article className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm" key={title}>
            <Icon aria-hidden="true" className="size-5 text-teal-700" />
            <h2 className="mt-4 font-semibold text-slate-950">{title}</h2>
            <p className="mt-2 text-sm leading-6 text-slate-600">{description}</p>
          </article>
        ))}
      </div>
    </section>
  );
}
