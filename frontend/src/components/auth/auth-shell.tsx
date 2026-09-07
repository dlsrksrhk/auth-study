import type {ReactNode} from "react";

import {Badge} from "@/components/ui/badge";

export function AuthShell({
                            eyebrow,
                            title,
                            description,
                            children,
                          }: {
  eyebrow: string;
  title: string;
  description: string;
  children: ReactNode;
}) {
  return (
      <main className="grid min-h-screen bg-slate-100 lg:grid-cols-[minmax(0,1fr)_minmax(34rem,0.72fr)]">
        <section
            className="relative hidden overflow-hidden bg-slate-950 p-12 text-slate-100 lg:flex lg:flex-col lg:justify-between">
          <div
              className="absolute inset-0 bg-[radial-gradient(circle_at_20%_20%,rgba(20,184,166,0.17),transparent_38%),radial-gradient(circle_at_80%_80%,rgba(59,130,246,0.13),transparent_34%)]"/>
          <div className="relative">
            <Badge className="border-teal-400/20 bg-teal-400/10 text-teal-200">AUTH STUDY</Badge>
            <h2 className="mt-8 max-w-lg text-4xl font-semibold tracking-tight">
              조직과 접근 권한을 한곳에서 관리합니다.
            </h2>
            <p className="mt-5 max-w-xl text-base leading-7 text-slate-400">
              회사, 조직, 사용자와 인증 상태를 운영하는 로컬 학습용 관리자 환경입니다.
            </p>
          </div>
          <p className="relative text-sm text-slate-500">Local administration workspace</p>
        </section>
        <section className="flex items-center justify-center p-6 sm:p-10 lg:p-16">
          <div className="w-full max-w-md">
            <p className="text-xs font-semibold uppercase tracking-[0.18em] text-teal-700">{eyebrow}</p>
            <h1 className="mt-3 text-3xl font-semibold tracking-tight text-slate-950">{title}</h1>
            <p className="mt-3 text-sm leading-6 text-slate-600">{description}</p>
            <div className="mt-8">{children}</div>
          </div>
        </section>
      </main>
  );
}
