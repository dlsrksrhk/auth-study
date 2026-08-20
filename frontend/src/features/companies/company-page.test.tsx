import { HttpResponse, http } from "msw";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

import CompanyPage from "@/app/(admin)/companies/page";
import { server } from "@/test/setup";

const replace = vi.fn();
let currentQuery = "";

vi.mock("next/navigation", () => ({
  usePathname: () => "/companies",
  useRouter: () => ({ replace }),
  useSearchParams: () => new URLSearchParams(currentQuery),
}));

const emptyPage = {
  content: [],
  page: 0,
  size: 20,
  totalElements: 0,
  totalPages: 0,
};

const company = {
  id: 8,
  code: "ACME",
  name: "Acme",
  emailDomain: "acme.example",
  status: "ACTIVE",
  version: 0,
  createdAt: "2026-08-20T01:00:00Z",
  updatedAt: "2026-08-20T01:00:00Z",
};

const companyB = {
  ...company,
  id: 9,
  code: "BETA",
  name: "Beta",
  emailDomain: "beta.example",
};

const defaultPositions = [
  ["EMPLOYEE", "사원", 10],
  ["ASSISTANT_MANAGER", "대리", 20],
  ["MANAGER", "과장", 30],
  ["DEPUTY_GENERAL_MANAGER", "차장", 40],
  ["GENERAL_MANAGER", "부장", 50],
].map(([code, name, level], index) => ({
  id: index + 1,
  companyId: 8,
  code,
  name,
  level,
  displayOrder: level,
  active: true,
  version: 0,
  createdAt: "2026-08-20T01:00:00Z",
  updatedAt: "2026-08-20T01:00:00Z",
}));

describe("CompanyPage", () => {
  beforeEach(() => {
    replace.mockReset();
    currentQuery = "";
  });

  it("creates a normalized company, refreshes the list, and shows five default positions", async () => {
    const user = userEvent.setup();
    let created = false;
    server.use(
      http.get("/api/v1/admin/companies", ({ request }) => {
        const params = new URL(request.url).searchParams;
        expect(Object.fromEntries(params)).toEqual({ page: "0", size: "20", sort: "code" });
        return HttpResponse.json(created ? { ...emptyPage, content: [company], totalElements: 1, totalPages: 1 } : emptyPage);
      }),
      http.post("/api/v1/admin/companies", async ({ request }) => {
        expect(await request.json()).toEqual({
          code: "ACME",
          name: "Acme",
          emailDomain: "acme.example",
        });
        created = true;
        return HttpResponse.json(company, {
          status: 201,
          headers: { Location: "/api/v1/admin/companies/ACME" },
        });
      }),
      http.get("/api/v1/admin/companies/ACME/positions", ({ request }) => {
        expect(Object.fromEntries(new URL(request.url).searchParams)).toEqual({
          page: "0",
          size: "20",
          sort: "displayOrder",
        });
        return HttpResponse.json({
          content: defaultPositions,
          page: 0,
          size: 20,
          totalElements: 5,
          totalPages: 1,
        });
      }),
    );

    render(<CompanyPage />);
    expect(await screen.findByText("등록된 회사가 없습니다.")).toBeVisible();
    await user.click(screen.getByRole("button", { name: "회사 생성" }));
    await user.type(screen.getByLabelText("코드"), " acme ");
    await user.type(screen.getByLabelText("회사명"), "Acme");
    await user.type(screen.getByLabelText("이메일 도메인"), "ACME.EXAMPLE");
    await user.click(screen.getByRole("button", { name: "저장" }));

    expect(await screen.findByRole("cell", { name: "ACME" })).toBeVisible();
    expect(await screen.findByText("기본 직위 5개가 준비되었습니다.")).toBeVisible();
    defaultPositions.forEach(({ name }) => expect(screen.getByText(name)).toBeVisible());
    expect(screen.getByRole("link", { name: "직위 관리" })).toHaveAttribute(
      "href",
      "/companies/ACME/positions",
    );
  });

  it("synchronizes debounced search with the URL", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/api/v1/admin/companies", () => HttpResponse.json(emptyPage)),
    );
    render(<CompanyPage />);
    await screen.findByText("등록된 회사가 없습니다.");

    await user.type(screen.getByLabelText("회사 검색"), "acme");
    expect(replace).not.toHaveBeenCalled();
    await waitFor(() => expect(replace).toHaveBeenCalledWith("/companies?search=acme", { scroll: false }));
  });

  it("focuses a 409 field error and exposes its trace id", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/api/v1/admin/companies", () => HttpResponse.json(emptyPage)),
      http.post("/api/v1/admin/companies", () =>
        HttpResponse.json(
          {
            type: "https://auth-study.local/problems/conflict",
            title: "Conflict",
            status: 409,
            detail: "Company code already exists.",
            code: "DUPLICATE_COMPANY_CODE",
            traceId: "trace-company-409",
            fieldErrors: [{ field: "code", message: "이미 사용 중인 코드입니다." }],
          },
          { status: 409 },
        ),
      ),
    );

    render(<CompanyPage />);
    await screen.findByText("등록된 회사가 없습니다.");
    await user.click(screen.getByRole("button", { name: "회사 생성" }));
    await user.type(screen.getByLabelText("코드"), "ACME");
    await user.type(screen.getByLabelText("회사명"), "Acme");
    await user.type(screen.getByLabelText("이메일 도메인"), "acme.example");
    await user.click(screen.getByRole("button", { name: "저장" }));

    expect(await screen.findByText("이미 사용 중인 코드입니다.")).toBeVisible();
    expect(screen.getByText(/trace-company-409/)).toBeVisible();
    await waitFor(() => expect(screen.getByLabelText("코드")).toHaveFocus());
  });

  it.each([
    ["page=Infinity", "", { page: "0", size: "20", sort: "code" }],
    ["page=1e2", "", { page: "0", size: "20", sort: "code" }],
    ["page=1.5", "", { page: "0", size: "20", sort: "code" }],
    ["page=-1", "", { page: "0", size: "20", sort: "code" }],
    ["page=2147483647&size=100", "size=100", { page: "0", size: "100", sort: "code" }],
    ["size=101&sort=bogus&status=PAUSED", "", { page: "0", size: "20", sort: "code" }],
  ] as const)("canonicalizes invalid query %s before issuing a request", async (invalidQuery, canonicalQuery, expectedRequest) => {
    const captured: Record<string, string>[] = [];
    currentQuery = invalidQuery;
    server.use(
      http.get("/api/v1/admin/companies", ({ request }) => {
        captured.push(Object.fromEntries(new URL(request.url).searchParams));
        return HttpResponse.json(emptyPage);
      }),
    );

    const view = render(<CompanyPage />);
    await waitFor(() => expect(replace).toHaveBeenCalledWith(`/companies${canonicalQuery ? `?${canonicalQuery}` : ""}`, { scroll: false }));
    expect(captured).toEqual([]);

    currentQuery = canonicalQuery;
    view.rerender(<CompanyPage />);
    await waitFor(() => expect(captured).toEqual([expectedRequest]));
  });

  it("re-reads valid URL state during back and forward navigation", async () => {
    const captured: Record<string, string>[] = [];
    currentQuery = "page=2&size=50&sort=name&status=ACTIVE&search=acme";
    server.use(
      http.get("/api/v1/admin/companies", ({ request }) => {
        captured.push(Object.fromEntries(new URL(request.url).searchParams));
        return HttpResponse.json({ ...emptyPage, page: captured.length === 1 ? 2 : 1, size: 50 });
      }),
    );
    const view = render(<CompanyPage />);
    await waitFor(() => expect(captured).toHaveLength(1));
    expect(screen.getByLabelText("회사 검색")).toHaveValue("acme");

    currentQuery = "page=1&size=50&sort=name&status=INACTIVE&search=zen";
    view.rerender(<CompanyPage />);
    await waitFor(() => expect(captured).toHaveLength(2));
    expect(captured[1]).toEqual({ page: "1", size: "50", sort: "name", status: "INACTIVE", search: "zen" });
    expect(screen.getByLabelText("회사 검색")).toHaveValue("zen");
  });

  it.each([
    ["zero positions", HttpResponse.json({ ...emptyPage, size: 20 })],
    ["four positions", HttpResponse.json({ ...emptyPage, content: defaultPositions.slice(0, 4), totalElements: 4, totalPages: 1 })],
    ["a verification failure", HttpResponse.json({
      type: "about:blank", title: "Failed", status: 500, code: "INTERNAL", traceId: "trace-defaults", fieldErrors: [],
    }, { status: 500 })],
  ])("keeps a successful company create committed when default position verification returns %s", async (_label, positionsResponse) => {
    let created = false;
    server.use(
      http.get("/api/v1/admin/companies", () => HttpResponse.json(created
        ? { ...emptyPage, content: [company], totalElements: 1, totalPages: 1 }
        : emptyPage)),
      http.post("/api/v1/admin/companies", () => {
        created = true;
        return HttpResponse.json(company, { status: 201, headers: { Location: "/api/v1/admin/companies/ACME" } });
      }),
      http.get("/api/v1/admin/companies/ACME/positions", () => positionsResponse),
    );

    const user = userEvent.setup();
    render(<CompanyPage />);
    await screen.findByText("등록된 회사가 없습니다.");
    await submitCompanyForm(user);

    expect(await screen.findByRole("cell", { name: "ACME" })).toBeVisible();
    expect(screen.queryByRole("dialog", { name: "회사 생성" })).not.toBeInTheDocument();
    expect(await screen.findByText("회사는 생성되었지만 기본 직위 5개를 확인하지 못했습니다.")).toBeVisible();
    expect(screen.queryByRole("button", { name: "저장" })).not.toBeInTheDocument();
  });

  it("closes the create form while default positions are still being verified", async () => {
    let created = false;
    let finishPositions!: () => void;
    const positionsGate = new Promise<void>((resolve) => { finishPositions = resolve; });
    server.use(
      http.get("/api/v1/admin/companies", () => HttpResponse.json(created
        ? { ...emptyPage, content: [company], totalElements: 1, totalPages: 1 }
        : emptyPage)),
      http.post("/api/v1/admin/companies", () => {
        created = true;
        return HttpResponse.json(company, { status: 201 });
      }),
      http.get("/api/v1/admin/companies/ACME/positions", async () => {
        await positionsGate;
        return HttpResponse.json({ ...emptyPage, content: defaultPositions, totalElements: 5, totalPages: 1 });
      }),
    );
    const user = userEvent.setup();
    render(<CompanyPage />);
    await screen.findByText("등록된 회사가 없습니다.");
    await submitCompanyForm(user);

    expect(await screen.findByText("기본 직위를 확인하는 중입니다.")).toBeVisible();
    expect(screen.queryByRole("dialog", { name: "회사 생성" })).not.toBeInTheDocument();
    finishPositions();
    expect(await screen.findByText("기본 직위 5개가 준비되었습니다.")).toBeVisible();
  });

  it.each(["resolve", "reject"] as const)(
    "keeps company B's provisioning result when company A later %s",
    async (lateOutcome) => {
      let releaseCompanyA!: () => void;
      let markCompanyAStarted!: () => void;
      let markCompanyAFinished!: () => void;
      const companyAGate = new Promise<void>((resolve) => { releaseCompanyA = resolve; });
      const companyAStarted = new Promise<void>((resolve) => { markCompanyAStarted = resolve; });
      const companyAFinished = new Promise<void>((resolve) => { markCompanyAFinished = resolve; });
      const createdCompanies: typeof company[] = [];
      let companyAAborted = false;

      server.use(
        http.get("/api/v1/admin/companies", () => HttpResponse.json({
          ...emptyPage,
          content: createdCompanies,
          totalElements: createdCompanies.length,
          totalPages: createdCompanies.length > 0 ? 1 : 0,
        })),
        http.post("/api/v1/admin/companies", async ({ request }) => {
          const input = await request.json() as { code: string };
          const saved = input.code === companyB.code ? companyB : company;
          createdCompanies.push(saved);
          return HttpResponse.json(saved, { status: 201 });
        }),
        http.get("/api/v1/admin/companies/ACME/positions", async ({ request }) => {
          request.signal.addEventListener("abort", () => { companyAAborted = true; }, { once: true });
          markCompanyAStarted();
          await companyAGate;
          markCompanyAFinished();
          return lateOutcome === "resolve"
            ? HttpResponse.json({ ...emptyPage, content: defaultPositions, totalElements: 5, totalPages: 1 })
            : HttpResponse.json({
              type: "about:blank", title: "Failed", status: 500, code: "INTERNAL", traceId: "trace-a", fieldErrors: [],
            }, { status: 500 });
        }),
        http.get("/api/v1/admin/companies/BETA/positions", () => HttpResponse.json({
          ...emptyPage,
          content: defaultPositions,
          totalElements: 5,
          totalPages: 1,
        })),
      );

      const user = userEvent.setup();
      render(<CompanyPage />);
      await screen.findByText("등록된 회사가 없습니다.");
      await submitCompanyForm(user);
      await companyAStarted;
      await submitCompanyForm(user, companyB);

      expect(await screen.findByText("기본 직위 5개가 준비되었습니다.")).toBeVisible();
      expect(screen.getByRole("link", { name: "직위 관리" })).toHaveAttribute("href", "/companies/BETA/positions");

      releaseCompanyA();
      await companyAFinished;
      await new Promise<void>((resolve) => window.setTimeout(resolve, 0));
      expect(companyAAborted).toBe(true);
      expect(screen.getByText("기본 직위 5개가 준비되었습니다.")).toBeVisible();
      expect(screen.getByRole("link", { name: "직위 관리" })).toHaveAttribute("href", "/companies/BETA/positions");
      expect(screen.queryByText("회사는 생성되었지만 기본 직위 5개를 확인하지 못했습니다.")).not.toBeInTheDocument();
    },
  );

  it("aborts an in-flight provisioning verification when the company table unmounts", async () => {
    let releasePositions!: () => void;
    let markStarted!: () => void;
    const positionsGate = new Promise<void>((resolve) => { releasePositions = resolve; });
    const started = new Promise<void>((resolve) => { markStarted = resolve; });
    let requestSignal: AbortSignal | undefined;
    server.use(
      http.get("/api/v1/admin/companies", () => HttpResponse.json(emptyPage)),
      http.post("/api/v1/admin/companies", () => HttpResponse.json(company, { status: 201 })),
      http.get("/api/v1/admin/companies/ACME/positions", async ({ request }) => {
        requestSignal = request.signal;
        markStarted();
        await positionsGate;
        return HttpResponse.json({ ...emptyPage, content: defaultPositions, totalElements: 5, totalPages: 1 });
      }),
    );

    const user = userEvent.setup();
    const view = render(<CompanyPage />);
    await screen.findByText("등록된 회사가 없습니다.");
    await submitCompanyForm(user);
    await started;
    view.unmount();

    expect(requestSignal?.aborted).toBe(true);
    releasePositions();
  });
});

async function submitCompanyForm(user: ReturnType<typeof userEvent.setup>, input = company) {
  await user.click(screen.getByRole("button", { name: "회사 생성" }));
  await user.type(screen.getByLabelText("코드"), input.code);
  await user.type(screen.getByLabelText("회사명"), input.name);
  await user.type(screen.getByLabelText("이메일 도메인"), input.emailDomain);
  await user.click(screen.getByRole("button", { name: "저장" }));
}
