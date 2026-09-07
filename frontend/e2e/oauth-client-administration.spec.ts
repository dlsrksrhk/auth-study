import { expect, test, type Page } from "@playwright/test";

// Disable collection up front, before login/create, rather than deleting files
// later. No console listeners, response-body attachments, snapshots or raw-value
// assertions are allowed here. Error-context page snapshots are off in config.
test.use({ trace: "off", screenshot: "off", video: "off", actionTimeout: 15_000 });

test("administrator manages a confidential OAuth client with one-time credentials", async ({ page, context }) => {
  let phase = "login";
  try {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    const suffix = `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`.toUpperCase();
    const companyCode = `O${suffix}`;
    const displayName = `OAuth E2E ${suffix}`;
    await page.goto("/login");
    await page.getByLabel("이메일").fill("admin@auth-study.local");
    await page.getByLabel("비밀번호", { exact: true }).fill("AuthStudy1234!");
    await page.getByRole("button", { name: "로그인", exact: true }).click();
    await expect(page.getByRole("heading", { name: "관리자 대시보드" })).toBeVisible();

    phase = "company selection";
    await expect(page.getByText("인증/인가 설정을 관리하려면 먼저 회사를 선택해 주세요.")).toBeVisible();
    await page.getByRole("link", { name: "인증/인가 설정", exact: true }).click();
    await page.getByRole("button", { name: "회사 생성", exact: true }).click();
    await page.getByLabel("코드", { exact: true }).fill(companyCode);
    await page.getByLabel("회사명").fill(displayName);
    await page.getByLabel("이메일 도메인").fill(`${suffix.toLowerCase()}.example`);
    await page.getByRole("dialog").getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("기본 직위 5개가 준비되었습니다.")).toBeVisible();
    await page.getByLabel("회사 선택 검색").fill(companyCode);
    await page.getByLabel("관리 회사").selectOption(companyCode);
    await page.getByRole("link", { name: "인증/인가 설정", exact: true }).click();
    await expect(page).toHaveURL(new RegExp(`/companies/${companyCode}/oauth-clients$`));

    phase = "create confidential client";
    await page.getByRole("link", { name: "OAuth client 생성" }).click();
    await page.getByLabel("표시 이름").fill(displayName);
    await page.getByLabel("Client 유형").selectOption("confidential");
    await page.getByLabel("Redirect URI", { exact: true }).fill("http://app.localhost/callback");
    // Submit using the keyboard; verify the actual modal focus trap below.
    await page.getByRole("button", { name: "저장", exact: true }).focus();
    await page.keyboard.press("Enter");
    phase = "created secret dialog";
    await verifyAndCloseSecret(page);
    await expect(page.getByRole("heading", { name: displayName, exact: true })).toBeVisible();

    phase = "detail requery excludes secret";
    const detailResponse = page.waitForResponse((response) => response.request().method() === "GET" && response.url().includes("/api/v1/") && /\/oauth-clients\/[^/?]+$/.test(response.url()));
    await page.reload();
    const detail = await (await detailResponse).json();
    expect(Object.keys(detail).some((key) => /^(oneTimeSecret|secret|clientSecret)$/.test(key))).toBe(false);
    await expect(page.getByRole("dialog", { name: "OAuth client secret" })).toHaveCount(0);
    await expect(page.locator("code")).toHaveCount(0);
    await page.getByRole("button", { name: "client_id 복사" }).click();
    await expect(page.getByRole("status").filter({ hasText: "client_id를 복사했습니다." })).toHaveAttribute("aria-live", "polite");
    await page.evaluate(() => navigator.clipboard.writeText(""));

    phase = "edit redirect and scopes";
    await page.getByLabel("Redirect URI", { exact: true }).fill("https://app.localhost/updated?exact=%2f");
    await page.getByRole("checkbox", { name: /profile/ }).check();
    await page.getByRole("checkbox", { name: /email/ }).check();
    await page.getByRole("button", { name: "저장", exact: true }).click();
    await expect(page.getByText("설정을 저장했습니다.")).toBeVisible();
    await page.reload();
    await expect(page.getByLabel("Redirect URI", { exact: true })).toHaveValue("https://app.localhost/updated?exact=%2f");
    await expect(page.getByRole("checkbox", { name: /profile/ })).toBeChecked();
    await expect(page.getByRole("checkbox", { name: /email/ })).toBeChecked();

    phase = "rotate secret";
    await confirmAction(page, "secret 회전");
    await verifyAndCloseSecret(page);
    phase = "revoke secret";
    await confirmAction(page, "secret 폐기");
    await expect(page.getByText("활성 secret 힌트: 없음")).toBeVisible();
    await expect(page.getByRole("dialog", { name: "OAuth client secret" })).toHaveCount(0);
    // Restore a valid credential before exercising status transitions.
    await confirmAction(page, "secret 회전");
    await verifyAndCloseSecret(page);
    phase = "disable confirmation";
    const disabled = page.waitForResponse((response) => response.url().endsWith("/disable") && response.request().method() === "POST");
    await confirmAction(page, "비활성화");
    phase = `disabled status (HTTP ${(await disabled).status()})`;
    await expect(page.getByText("상태: DISABLED", { exact: false })).toBeVisible();
    phase = "enable confirmation";
    await confirmAction(page, "활성화");
    phase = "enabled status";
    await expect(page.getByText("상태: ACTIVE", { exact: false })).toBeVisible();

    phase = "protocol trace and browser history";
    await page.getByRole("link", { name: "Protocol 이벤트 보기" }).click();
    await expect(page.getByRole("heading", { name: "Protocol 이벤트", exact: true })).toBeVisible();
    await expect(page.getByText("조건에 맞는 protocol 이벤트가 없습니다.")).toBeVisible();
    await page.getByLabel("이벤트 유형").selectOption("AUTHORIZATION_CODE_ISSUED");
    await expect(page).toHaveURL(/type=AUTHORIZATION_CODE_ISSUED/);
    await page.goBack();
    await expect(page.getByLabel("이벤트 유형")).toHaveValue("");
    await expect(page.getByRole("dialog", { name: "OAuth client secret" })).toHaveCount(0);
  } catch {
    // Locator failures can quote DOM text, even with media disabled. Never pass
    // the original Error/cause to Playwright's reporter or error-context writer.
    throw new Error(`OAuth administration failed during: ${phase}. Sensitive diagnostics suppressed.`);
  } finally {
    await page.evaluate(() => navigator.clipboard.writeText("")).catch(() => {});
  }
});

async function verifyAndCloseSecret(page: Page) {
  const dialog = page.getByRole("dialog", { name: "OAuth client secret", exact: true });
  await expect(dialog).toBeVisible();
  await expect(dialog).toHaveAccessibleDescription(/지금 한 번만/);
  // Only return booleans from the browser; raw credentials never enter test
  // values, error messages, snapshots, attachments, or test names.
  expect(await dialog.locator("code").evaluate((node) => Boolean(node.textContent?.trim()))).toBe(true);
  if (process.env.OAUTH_E2E_FAILURE_PROBE === "1") throw new Error("intentional sensitive-step failure");
  const copy = dialog.getByRole("button", { name: "복사", exact: true });
  await copy.focus();
  await page.keyboard.press("Enter");
  await expect(dialog.getByRole("status")).toHaveText("secret을 복사했습니다.");
  await expect(dialog.getByRole("status")).toHaveAttribute("aria-live", "polite");
  expect(await dialog.locator("code").evaluate(async (node) => (await navigator.clipboard.readText()) === node.textContent)).toBe(true);
  await page.keyboard.press("Tab");
  await expect(dialog.getByRole("button", { name: "닫기", exact: true })).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(copy).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toHaveCount(0);
  await page.evaluate(() => navigator.clipboard.writeText(""));
}

async function confirmAction(page: Page, action: string) {
  await page.getByRole("button", { name: action, exact: true }).click();
  const dialog = page.getByRole("dialog", { name: `${action} 확인`, exact: true });
  await expect(dialog).toHaveAccessibleDescription(/폐기|로그인/);
  await dialog.getByRole("button", { name: `${action} 확인`, exact: true }).click();
}
