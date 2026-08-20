import { HttpResponse, http } from "msw";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, it, vi } from "vitest";

import { server } from "@/test/setup";
import { CompanySwitcher } from "./company-switcher";

const replace = vi.fn();

vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));

const company = (code: string, status: "ACTIVE" | "INACTIVE" = "ACTIVE") => ({
  id: Number(code.replace(/\D/g, "")) || 999,
  code,
  name: code === "OFFPAGE" ? "Off Page" : `Company ${code}`,
  emailDomain: `${code.toLowerCase()}.example`,
  status,
  version: 0,
  createdAt: "2026-08-20T01:00:00Z",
  updatedAt: "2026-08-20T01:00:00Z",
});

beforeEach(() => replace.mockReset());

it("hydrates an inactive selected company that is outside the current server page", async () => {
  const firstPage = Array.from({ length: 20 }, (_, index) => company(`C${String(index).padStart(3, "0")}`));
  server.use(
    http.get("/api/v1/admin/companies", ({ request }) => {
      expect(Object.fromEntries(new URL(request.url).searchParams)).toEqual({ page: "0", size: "20", sort: "code" });
      return HttpResponse.json({ content: firstPage, page: 0, size: 20, totalElements: 101, totalPages: 6 });
    }),
    http.get("/api/v1/admin/companies/OFFPAGE", () => HttpResponse.json(company("OFFPAGE", "INACTIVE"))),
  );

  render(<CompanySwitcher pathname="/" selectedCompanyCode="OFFPAGE" />);

  expect(await screen.findByRole("option", { name: "Off Page (OFFPAGE) · 비활성" })).toBeVisible();
  expect(screen.getByLabelText("관리 회사")).toHaveValue("OFFPAGE");
  expect(screen.getByText("1 / 6 페이지")).toBeVisible();
});

it("uses server paging and debounced search instead of a one-shot first 100 request", async () => {
  const user = userEvent.setup();
  const captured: Record<string, string>[] = [];
  server.use(
    http.get("/api/v1/admin/companies", ({ request }) => {
      const query = Object.fromEntries(new URL(request.url).searchParams);
      captured.push(query);
      return HttpResponse.json({ content: [], page: Number(query.page), size: 20, totalElements: 101, totalPages: 6 });
    }),
  );

  render(<CompanySwitcher pathname="/" selectedCompanyCode={null} />);
  await waitFor(() => expect(captured).toEqual([{ page: "0", size: "20", sort: "code" }]));
  await user.click(screen.getByRole("button", { name: "다음 회사 페이지" }));
  await waitFor(() => expect(captured.at(-1)).toEqual({ page: "1", size: "20", sort: "code" }));
  await user.type(screen.getByLabelText("회사 선택 검색"), "zen");
  await waitFor(() => expect(captured.at(-1)).toEqual({ page: "0", size: "20", sort: "code", search: "zen" }));
});

it("refreshes options after a company-created invalidation event", async () => {
  let calls = 0;
  server.use(http.get("/api/v1/admin/companies", () => {
    calls += 1;
    return HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  }));
  render(<CompanySwitcher pathname="/companies" selectedCompanyCode={null} />);
  await waitFor(() => expect(calls).toBe(1));

  window.dispatchEvent(new Event("auth-study:company-created"));
  await waitFor(() => expect(calls).toBe(2));
});

it("renders a pure company administrator as fixed text without loading the system company list", async () => {
  let calls = 0;
  server.use(http.get("/api/v1/admin/companies", () => {
    calls += 1;
    return HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  }));

  render(<CompanySwitcher fixedCompanyCode="ACME" pathname="/" selectedCompanyCode="ACME" />);
  expect(screen.getByText("ACME")).toBeVisible();
  await new Promise((resolve) => setTimeout(resolve, 0));
  expect(calls).toBe(0);
});
