import {render, screen, waitFor} from "@testing-library/react";
import {beforeEach, describe, expect, it, vi} from "vitest";

import {dashboardApi} from "./dashboard-api";
import {SummaryCards} from "./summary-cards";

vi.mock("./dashboard-api", () => ({dashboardApi: {summary: vi.fn()}}));

describe("SummaryCards", () => {
  beforeEach(() => vi.mocked(dashboardApi.summary).mockReset());

  it("선택한 회사의 서버 집계 수치를 표시한다", async () => {
    vi.mocked(dashboardApi.summary).mockResolvedValue({
      activeUsers: 7,
      departments: 3,
      lockedUsers: 2,
      resignedUsers: 1
    });
    render(<SummaryCards companyCode="acme"/>);
    expect(screen.getByText("대시보드 요약을 불러오는 중입니다.")).toBeInTheDocument();
    expect(await screen.findByText("7")).toBeInTheDocument();
    expect(screen.getByText("활성 사용자")).toBeInTheDocument();
    expect(screen.getByText("부서")).toBeInTheDocument();
    expect(dashboardApi.summary).toHaveBeenCalledWith("ACME", expect.any(AbortSignal));
  });

  it("회사가 없으면 선택 안내만 표시한다", () => {
    render(<SummaryCards companyCode={null}/>);
    expect(screen.getByText("관리할 회사를 먼저 선택해 주세요.")).toBeInTheDocument();
    expect(dashboardApi.summary).not.toHaveBeenCalled();
  });

  it("이전 회사의 늦은 응답을 무시한다", async () => {
    let resolveOld!: (value: {
      activeUsers: number;
      departments: number;
      lockedUsers: number;
      resignedUsers: number
    }) => void;
    vi.mocked(dashboardApi.summary)
        .mockImplementationOnce(() => new Promise((resolve) => {
          resolveOld = resolve;
        }))
        .mockResolvedValueOnce({activeUsers: 9, departments: 8, lockedUsers: 7, resignedUsers: 6});
    const view = render(<SummaryCards companyCode="OLD"/>);
    view.rerender(<SummaryCards companyCode="NEW"/>);
    expect(await screen.findByText("9")).toBeInTheDocument();
    resolveOld({activeUsers: 1, departments: 1, lockedUsers: 1, resignedUsers: 1});
    await waitFor(() => expect(screen.queryByText("1")).not.toBeInTheDocument());
  });
});
