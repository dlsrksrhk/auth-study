import { defineConfig, devices } from "@playwright/test";

// Playwright 1.62 also captures an ariaSnapshot into error-context.md independently
// of trace/screenshot settings (lib/index.js::_takePageSnapshot). Never capture
// authenticated pages containing one-time credentials, including on failure.
process.env.PLAYWRIGHT_NO_COPY_PROMPT = "1";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  // HTML serializes caught assertion step errors as well as final errors; these
  // can contain DOM values even when the test rethrows a sanitized error.
  // Keep only the final-status console reporter for credential-bearing flows.
  reporter: [["list"]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "off",
    screenshot: "off",
    video: "off",
    ...devices["Desktop Chrome"],
  },
  webServer: [
    {
      command: ".\\gradlew.bat bootRun --args=\"--spring.profiles.active=dev\"",
      cwd: "../backend",
      url: "http://localhost:8080/api/v1/auth/me",
      timeout: 120_000,
      reuseExistingServer: false,
    },
    {
      command: "npm run dev -- --hostname 127.0.0.1 --port 3000",
      cwd: ".",
      // Next dev forwards browser console messages to its server output.
      stdout: "ignore",
      stderr: "ignore",
      url: "http://localhost:3000/login",
      timeout: 120_000,
      reuseExistingServer: false,
    },
  ],
});
