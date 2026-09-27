import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

const project = { id: "9e0b1c0d-1b14-4b75-9a5c-c8a646100001", name: "Test project", createdAt: "2026-09-27T00:00:00Z" };
const fileJob = {
  id: "8b0a0c0d-1b14-4b75-9a5c-c8a646100002", projectId: project.id, jobType: "PROCESS_FILE", status: "COMPLETED", priority: "DEFAULT",
  createdAt: "2026-09-27T00:00:00Z", completedAt: "2026-09-27T00:01:00Z",
  runs: [{ id: "7a0a0c0d-1b14-4b75-9a5c-c8a646100003", runNumber: 1, status: "COMPLETED", attemptCount: 1, maxAttempts: 4, executions: [{
    id: "6a0a0c0d-1b14-4b75-9a5c-c8a646100004", attemptNumber: 1, status: "COMPLETED", startedAt: "2026-09-27T00:00:01Z", finishedAt: "2026-09-27T00:01:00Z",
    result: { type: "PROCESS_FILE", sourceAssetId: "5a0a0c0d-1b14-4b75-9a5c-c8a646100005", cleanedAssetId: "4a0a0c0d-1b14-4b75-9a5c-c8a646100006", errorAssetId: "3a0a0c0d-1b14-4b75-9a5c-c8a646100007", totalRows: 12, validRows: 10, rejectedRows: 2, duplicatesRemoved: 1 }
  }] }]
};
const asset = { id: "5a0a0c0d-1b14-4b75-9a5c-c8a646100005", kind: "SOURCE_CSV", filename: "orders.csv", contentType: "text/csv", sizeBytes: 512, sha256: "a".repeat(64), rowCount: 12, header: ["id", "amount"], createdAt: "2026-09-27T00:00:00Z" };

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => localStorage.setItem("job-platform-session", JSON.stringify({ accessToken: "test-access", refreshToken: "test-refresh", expiresIn: 900 })));
  await page.route("**/api/v1/**", async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    const body = path === "/api/v1/projects" ? [project]
      : path.endsWith("/jobs/" + fileJob.id) ? fileJob
      : path.endsWith("/jobs") ? { items: [fileJob] }
      : path.endsWith("/api-keys") ? []
      : path.endsWith("/files") ? [asset]
      : path.endsWith("/preview") && url.searchParams.get("reason") === "DUPLICATE_REMOVED" ? { header: ["row", "reason", "id"], rows: [["7", "DUPLICATE_REMOVED", "123"]], hasMore: false }
      : path.endsWith("/preview") && url.searchParams.get("excludeReason") === "DUPLICATE_REMOVED" ? { header: ["row", "reason", "id"], rows: [["4", "MISSING_REQUIRED_VALUE", "456"]], hasMore: false }
      : path.endsWith("/preview") ? { header: ["id", "amount"], rows: [["123", "42.00"]], hasMore: true }
      : { code: "NOT_FOUND", message: "No mocked response for " + path };
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });
});

test("anonymous visitors receive a labelled sign-in form", async ({ browser }) => {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Work moves, even while you don’t." })).toBeVisible();
  await expect(page.getByLabel("Email")).toBeVisible();
  await expect(page.getByLabel("Password")).toBeVisible();
  await context.close();
});

test("dashboard exposes focused file, job, and result routes without losing accessibility", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Job history" })).toBeVisible();
  await page.getByRole("button", { name: "Open details" }).click();
  await expect(page).toHaveURL(new RegExp("/jobs/" + fileJob.id + "$"));
  await expect(page.getByRole("heading", { name: "Process File" })).toBeVisible();
  await page.getByRole("button", { name: "View result" }).click();
  await expect(page).toHaveURL(new RegExp("/jobs/" + fileJob.id + "/result$"));
  await expect(page.getByRole("tab", { name: "Duplicates removed" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Valid rows" })).toBeVisible();
  await expect(page.getByRole("cell", { name: "123" })).toBeVisible();
  await page.getByRole("tab", { name: "Rejected rows" }).click();
  await expect(page.getByRole("cell", { name: "MISSING_REQUIRED_VALUE" })).toBeVisible();
  await page.getByRole("tab", { name: "Duplicates removed" }).click();
  await expect(page.getByRole("cell", { name: "DUPLICATE_REMOVED" })).toBeVisible();
  await page.getByRole("button", { name: "Files" }).click();
  await expect(page).toHaveURL(/\/files$/);
  await expect(page.getByRole("heading", { name: "CSV files" })).toBeVisible();
  await page.getByRole("button", { name: "API setup" }).click();
  await expect(page).toHaveURL(/\/api$/);
  await expect(page.getByRole("heading", { name: "Schedule jobs from your application" })).toBeVisible();
  await expect(page.getByText("X-API-Key: $JOB_PLATFORM_API_KEY", { exact: true })).toBeVisible();

  const accessibility = await new AxeBuilder({ page }).include(".route-page").analyze();
  const serious = accessibility.violations.filter(issue => issue.impact === "serious" || issue.impact === "critical");
  expect(serious, JSON.stringify(serious, null, 2)).toEqual([]);
});

test("creating a workspace discards the previous account's project context", async ({ browser }) => {
  const oldProject = { id: "0e0b1c0d-1b14-4b75-9a5c-c8a646100001", name: "Previous project", createdAt: "2026-09-27T00:00:00Z" };
  const newProject = { id: "1e0b1c0d-1b14-4b75-9a5c-c8a646100001", name: "New workspace", createdAt: "2026-09-27T00:00:00Z" };
  const oldSession = { accessToken: "old-access", refreshToken: "old-refresh", expiresIn: 900 };
  const newSession = { accessToken: "new-access", refreshToken: "new-refresh", expiresIn: 900 };
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.addInitScript(value => localStorage.setItem("job-platform-session", JSON.stringify(value)), oldSession);
  await page.route("**/api/v1/**", async route => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const token = request.headers().authorization;
    if (path === "/api/v1/auth/register") {
      await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify(newSession) });
      return;
    }
    if (path === "/api/v1/projects") {
      await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(token === "Bearer new-access" ? [newProject] : [oldProject]) });
      return;
    }
    if (path.includes(oldProject.id) && token === "Bearer new-access") {
      await route.fulfill({ status: 404, contentType: "application/json", body: JSON.stringify({ code: "NOT_FOUND", message: "Project not found." }) });
      return;
    }
    const body = path.endsWith("/jobs") ? { items: [] } : [];
    await route.fulfill({ status: 200, contentType: "application/json", body: JSON.stringify(body) });
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Job history" })).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.getByRole("button", { name: "Create a new workspace" }).click();
  await page.getByLabel("First project name").fill("New workspace");
  await page.getByLabel("Email").fill("new.workspace@example.test");
  await page.getByLabel("Password").fill("a-safe-test-password");
  await page.getByRole("button", { name: "Create workspace" }).click();

  await expect(page.getByLabel("Active project")).toHaveValue(newProject.id);
  await expect(page.locator(".error.banner")).toHaveCount(0);
  await context.close();
});
