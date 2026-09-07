import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeEach, expect, it, vi } from "vitest";

import {
  OAuthSecretOperationProvider,
  useOAuthSecretOperations,
} from "./oauth-secret-operation-provider";

const secret = "oauth-secret-visible-once";

const storageSpies = ["getItem", "setItem", "removeItem", "clear"].map((method) =>
  vi.spyOn(Storage.prototype, method as keyof Storage),
);

function SecretControls({ afterOpen }: { afterOpen?: () => void }) {
  const operations = useOAuthSecretOperations();
  return (
    <button
      type="button"
      onClick={() => {
        operations.open({
          clientId: "client-123",
          displayName: "급여 서비스",
          oneTimeSecret: secret,
        });
        afterOpen?.();
      }}
    >
      secret 열기
    </button>
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText: vi.fn().mockResolvedValue(undefined) },
  });
});

it("keeps the secret in provider memory across child navigation, then permanently clears it on close", async () => {
  const user = userEvent.setup();

  function RouteHarness() {
    const [route, setRoute] = useState<"create" | "detail">("create");
    return (
      <OAuthSecretOperationProvider>
        {route === "create" ? <SecretControls afterOpen={() => setRoute("detail")} /> : <p>client detail</p>}
      </OAuthSecretOperationProvider>
    );
  }

  render(<RouteHarness />);
  await user.click(screen.getByRole("button", { name: "secret 열기" }));

  expect(screen.getByText("client detail")).toBeVisible();
  expect(screen.getByRole("dialog")).toHaveAccessibleName("OAuth client secret");
  expect(screen.getByRole("dialog")).toHaveTextContent("급여 서비스");
  expect(screen.getByRole("dialog")).toHaveTextContent("client-123");
  expect(screen.getByText(secret)).toHaveClass("font-mono");

  await user.click(screen.getByRole("button", { name: "닫기" }));
  expect(screen.queryByText(secret)).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /다시 보기/ })).not.toBeInTheDocument();
});

it("uses only the clipboard for copying and never includes the secret in copy feedback", async () => {
  const user = userEvent.setup();
  const writeText = vi.fn().mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText },
  });
  render(
    <OAuthSecretOperationProvider>
      <SecretControls />
    </OAuthSecretOperationProvider>,
  );

  await user.click(screen.getByRole("button", { name: "secret 열기" }));
  await user.click(screen.getByRole("button", { name: "복사" }));

  expect(writeText).toHaveBeenCalledOnce();
  expect(writeText).toHaveBeenCalledWith(secret);
  const feedback = await screen.findByRole("status");
  expect(feedback).toHaveTextContent("secret을 복사했습니다.");
  expect(feedback).not.toHaveTextContent(secret);
  expect(storageSpies.every((spy) => spy.mock.calls.length === 0)).toBe(true);
});

it("discards the secret on provider unmount without touching browser storage", async () => {
  const user = userEvent.setup();
  const view = render(
    <OAuthSecretOperationProvider>
      <SecretControls />
    </OAuthSecretOperationProvider>,
  );

  await user.click(screen.getByRole("button", { name: "secret 열기" }));
  expect(screen.getByText(secret)).toBeVisible();
  view.unmount();

  render(
    <OAuthSecretOperationProvider>
      <p>new provider</p>
    </OAuthSecretOperationProvider>,
  );
  expect(screen.queryByText(secret)).not.toBeInTheDocument();
  expect(storageSpies.every((spy) => spy.mock.calls.length === 0)).toBe(true);
});
