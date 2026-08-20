import { expect, test, type Page, type Response } from "@playwright/test";

test("system admin provisions a company and company admin manages organization", async ({ page, context, browser }) => {
  const suffix = `${Date.now()}${Math.random().toString(36).slice(2, 7)}`.slice(-14).toUpperCase();
  const companyCode = `E${suffix}`.slice(0, 15);
  const companyDomain = `e2e-${suffix.toLowerCase()}.example`;
  const companyAdminEmail = `admin01@${companyDomain}`;
  const regularEmail = `u001@${companyDomain}`;
  const changedPassword = `Changed-${suffix}a1!`;

  await page.goto("/login");
  const loginResponsePromise = page.waitForResponse((response) => response.url().endsWith("/api/v1/auth/login") && response.request().method() === "POST");
  await login(page, "admin@auth-study.local", "AuthStudy1234!");
  const loginResponse = await loginResponsePromise;
  expect(loginResponse.status()).toBe(200);
  await expectRefreshSetCookie(loginResponse, "positive");
  await expect(page.getByRole("heading", { name: "관리자 대시보드" })).toBeVisible();
  const cookie = (await context.cookies()).find((item) => item.name === "AUTH_STUDY_REFRESH");
  expect(cookie).toMatchObject({ httpOnly: true, sameSite: "Lax", path: "/api/v1/auth", secure: false });

  const refreshResponsePromise = page.waitForResponse((response) => response.url().endsWith("/api/v1/auth/refresh") && response.request().method() === "POST");
  const refreshStatus = await page.evaluate(async () => (await fetch("/api/v1/auth/refresh", { method: "POST" })).status);
  const refreshResponse = await refreshResponsePromise;
  expect(refreshStatus).toBe(200);
  expect(refreshResponse.status()).toBe(200);
  await expectRefreshSetCookie(refreshResponse, "positive");
  const rotatedCookie = (await context.cookies()).find((item) => item.name === "AUTH_STUDY_REFRESH");
  expect(rotatedCookie).toMatchObject({ httpOnly: true, sameSite: "Lax", path: "/api/v1/auth", secure: false });
  const evilRefresh = await page.request.post("/api/v1/auth/refresh", { headers: { Origin: "https://evil.example" } });
  expect(evilRefresh.status()).toBe(403);

  await page.getByRole("link", { name: "회사", exact: true }).click();
  await page.getByRole("button", { name: "회사 생성" }).click();
  await page.getByLabel("코드").fill(companyCode);
  await page.getByLabel("회사명").fill(`E2E ${suffix}`);
  await page.getByLabel("이메일 도메인").fill(companyDomain);
  await page.getByRole("dialog").getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByText("기본 직위 5개가 준비되었습니다.")).toBeVisible();
  await expect(page.getByText("사원", { exact: true })).toBeVisible();
  await page.getByLabel("관리 회사").selectOption(companyCode);
  await expect(page).toHaveURL(new RegExp(`companyCode=${companyCode}`));

  await page.getByRole("link", { name: "사용자", exact: true }).click();
  const adminTemporaryPassword = await createUser(page, {
    code: "ADMIN01", employeeNumber: `A-${suffix}`, name: "회사 관리자", email: companyAdminEmail,
  });
  await openUser(page, "ADMIN01");
  await page.getByRole("link", { name: "부서", exact: true }).click();
  await createDepartment(page, "HQ", "본사", "");
  await page.getByRole("link", { name: "사용자", exact: true }).click();
  await openUser(page, "ADMIN01");
  await addMembership(page, "HQ", "HEAD", true);
  await activateUser(page);
  const preGrantContext = await browser.newContext();
  const preGrantPage = await preGrantContext.newPage();
  await preGrantPage.goto("http://localhost:3000/login");
  const forcedLoginResponsePromise = preGrantPage.waitForResponse((response) => response.url().endsWith("/api/v1/auth/login") && response.request().method() === "POST");
  await login(preGrantPage, companyAdminEmail, adminTemporaryPassword);
  const forcedLoginResponse = await forcedLoginResponsePromise;
  expect(forcedLoginResponse.status()).toBe(200);
  expect(await refreshSetCookie(forcedLoginResponse)).toBeUndefined();
  await expect(preGrantPage).toHaveURL(/\/change-password$/);
  expect((await preGrantContext.cookies()).find((item) => item.name === "AUTH_STUDY_REFRESH")).toBeUndefined();
  await preGrantPage.getByLabel("현재 비밀번호").fill(adminTemporaryPassword);
  await preGrantPage.getByLabel("새 비밀번호", { exact: true }).fill(changedPassword);
  await preGrantPage.getByLabel("새 비밀번호 확인").fill(changedPassword);
  await preGrantPage.getByRole("button", { name: "비밀번호 변경" }).click();
  await expect(preGrantPage).toHaveURL(/\/login$/);
  await login(preGrantPage, companyAdminEmail, changedPassword);
  await expect(preGrantPage).toHaveURL(/\/account$/);
  const normalLoginCookie = (await preGrantContext.cookies()).find((item) => item.name === "AUTH_STUDY_REFRESH");
  expect(normalLoginCookie).toMatchObject({ httpOnly: true, sameSite: "Lax", path: "/api/v1/auth", secure: false });
  await page.getByRole("button", { name: "회사 관리자 지정" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "권한 변경 확인" }).click();
  await expect(page.getByText("회사 관리자 권한을 지정했습니다.")).toBeVisible();
  expect(await preGrantPage.evaluate(async () => (await fetch("/api/v1/auth/refresh", { method: "POST" })).status)).toBe(401);
  await preGrantContext.close();

  const logoutResponsePromise = page.waitForResponse((response) => response.url().endsWith("/api/v1/auth/logout") && response.request().method() === "POST");
  await page.getByRole("button", { name: "로그아웃" }).click();
  const logoutResponse = await logoutResponsePromise;
  expect(logoutResponse.status()).toBe(204);
  await expectRefreshSetCookie(logoutResponse, "deleted");
  await expect(page).toHaveURL(/\/login$/);
  await expect.poll(async () => (await context.cookies()).find((item) => item.name === "AUTH_STUDY_REFRESH")).toBeUndefined();

  await login(page, companyAdminEmail, changedPassword);
  await expect(page.getByText(companyCode, { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "부서", exact: true }).click();
  await createDepartment(page, "DEV", "개발", "HQ");
  await createDepartment(page, "API", "API 개발", "DEV");
  await expect(page.getByRole("treeitem", { name: /API API 개발/ })).toBeVisible();

  await page.getByRole("link", { name: "사용자", exact: true }).click();
  await createUser(page, { code: "U001", employeeNumber: `U-${suffix}`, name: "일반 사용자", email: regularEmail });
  await openUser(page, "U001");
  await addMembership(page, "DEV", "MEMBER", true);
  await addMembership(page, "API", "MEMBER", false);
  await activateUser(page);
  const devMembership = page.getByRole("region", { name: "활성 소속" }).locator("div.rounded-lg").filter({ hasText: "DEV" });
  await expect(devMembership.getByText("주 소속", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "활성 소속" }).getByText("API", { exact: true })).toBeVisible();

  await page.getByRole("link", { name: "대시보드", exact: true }).first().click();
  await expect(page.locator("article").filter({ hasText: "활성 사용자" }).getByText("2", { exact: true })).toBeVisible();
  await expect(page.locator("article").filter({ hasText: "부서" }).getByText("3", { exact: true })).toBeVisible();
  await page.getByRole("link", { name: "감사 로그", exact: true }).click();
  await expect(page.getByRole("heading", { name: "감사 로그" })).toBeVisible();
  await expect(page.getByText("USER_CREATE", { exact: true }).first()).toBeVisible();
  await expect(page.getByText("MEMBERSHIP_CREATE", { exact: true }).first()).toBeVisible();
  await expect(page.getByRole("button", { name: /수정|삭제/ })).toHaveCount(0);
});

async function login(page: Page, email: string, password: string) {
  await page.getByLabel("이메일").fill(email);
  await page.getByLabel("비밀번호").fill(password);
  await page.getByRole("button", { name: "로그인" }).click();
}

async function createUser(page: Page, input: { code: string; employeeNumber: string; name: string; email: string }) {
  await page.getByRole("button", { name: "사용자 생성" }).click();
  const dialog = page.getByRole("dialog", { name: "사용자 생성" });
  await dialog.getByLabel("사용자 코드").fill(input.code);
  await dialog.getByLabel("사번").fill(input.employeeNumber);
  await dialog.getByLabel("이름").fill(input.name);
  await dialog.getByLabel("로그인 이메일").fill(input.email);
  await dialog.getByLabel("전화번호").fill("010-1234-5678");
  await dialog.getByLabel("입사일").fill("2026-08-20");
  await dialog.getByLabel("근무지").fill("서울");
  await dialog.getByLabel("직위").selectOption("EMPLOYEE");
  await dialog.getByRole("button", { name: "사용자 생성", exact: true }).click();
  const secretDialog = page.getByRole("dialog", { name: "사용자 생성 임시 비밀번호" });
  const secret = (await secretDialog.locator("code").textContent())?.trim();
  expect(secret).toBeTruthy();
  await secretDialog.getByRole("button", { name: "비밀번호 확인 완료" }).click();
  return secret!;
}

async function openUser(page: Page, code: string) {
  await page.getByLabel("사용자 검색").fill(code);
  await expect(page).toHaveURL(new RegExp(`search=${code}`));
  const row = page.getByRole("row").filter({ hasText: code });
  await expect(row).toBeVisible();
  await row.getByText("상세", { exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/users/${code}$`));
  await expect(page.getByText(`${code}`, { exact: false }).first()).toBeVisible();
}

async function createDepartment(page: Page, code: string, name: string, parent: string) {
  await page.getByRole("button", { name: "부서 생성" }).click();
  const dialog = page.getByRole("dialog", { name: "부서 생성" });
  await dialog.getByLabel("부서 코드").fill(code);
  await dialog.getByLabel("부서명").fill(name);
  if (parent) await dialog.getByLabel("상위 부서").selectOption(parent);
  await dialog.getByRole("button", { name: "저장", exact: true }).click();
  await expect(page.getByRole("treeitem", { name: new RegExp(`^${code} `) })).toBeVisible();
}

async function addMembership(page: Page, department: string, role: "HEAD" | "MEMBER", primary: boolean) {
  await page.getByLabel("부서 추가").selectOption(department);
  await page.getByLabel("역할", { exact: true }).selectOption(role);
  const primaryCheckbox = page.getByRole("checkbox", { name: "주 소속" });
  if (primary) await primaryCheckbox.check(); else await primaryCheckbox.uncheck();
  await page.getByRole("button", { name: "소속 추가" }).click();
  await expect(page.getByRole("region", { name: "활성 소속" }).getByText(department, { exact: true })).toBeVisible();
}

async function activateUser(page: Page) {
  await page.getByRole("button", { name: "사용자 활성화" }).click();
  await page.getByRole("dialog", { name: "사용자를 활성화할까요?" }).getByRole("button", { name: "확인" }).click();
  await expect(page.getByText("ACTIVE", { exact: true })).toBeVisible();
}

type ParsedSetCookie = {
  name: string;
  value: string;
  attributes: Map<string, string | true>;
};

async function refreshSetCookie(response: Response): Promise<ParsedSetCookie | undefined> {
  const headers = await response.headersArray();
  const values = headers
    .filter((header) => header.name.toLowerCase() === "set-cookie")
    .map((header) => parseSetCookie(header.value));
  return values.find((cookie) => cookie.name === "AUTH_STUDY_REFRESH");
}

function parseSetCookie(value: string): ParsedSetCookie {
  const [nameValue, ...rawAttributes] = value.split(";");
  const separator = nameValue.indexOf("=");
  if (separator <= 0) throw new Error(`Invalid Set-Cookie header: ${value}`);
  const attributes = new Map<string, string | true>();
  for (const rawAttribute of rawAttributes) {
    const attribute = rawAttribute.trim();
    if (!attribute) continue;
    const equals = attribute.indexOf("=");
    if (equals === -1) attributes.set(attribute.toLowerCase(), true);
    else attributes.set(attribute.slice(0, equals).trim().toLowerCase(), attribute.slice(equals + 1).trim());
  }
  return { name: nameValue.slice(0, separator).trim(), value: nameValue.slice(separator + 1), attributes };
}

async function expectRefreshSetCookie(response: Response, maxAge: "positive" | "deleted") {
  const cookie = await refreshSetCookie(response);
  expect(cookie, "AUTH_STUDY_REFRESH Set-Cookie header").toBeDefined();
  expect(cookie!.attributes.get("httponly")).toBe(true);
  expect(String(cookie!.attributes.get("samesite")).toLowerCase()).toBe("lax");
  expect(cookie!.attributes.get("path")).toBe("/api/v1/auth");
  expect(cookie!.attributes.has("secure")).toBe(false);
  const parsedMaxAge = Number(cookie!.attributes.get("max-age"));
  if (maxAge === "deleted") {
    expect(cookie!.value).toBe("");
    expect(parsedMaxAge).toBe(0);
  } else {
    expect(cookie!.value.length).toBeGreaterThan(0);
    expect(Number.isInteger(parsedMaxAge)).toBe(true);
    expect(parsedMaxAge).toBeGreaterThan(0);
  }
}
