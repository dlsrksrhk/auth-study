import { defineConfig, devices } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
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
      url: "http://localhost:3000/login",
      timeout: 120_000,
      reuseExistingServer: false,
    },
  ],
});
