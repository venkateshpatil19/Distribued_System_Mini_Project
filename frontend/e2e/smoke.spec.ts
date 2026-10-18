import { expect, test } from "@playwright/test";

const PAGES = ["/", "/dashboard", "/flags", "/rollouts", "/agents", "/topology", "/experiments", "/docs"];

for (const path of PAGES) {
  test(`page ${path} renders with one h1 and no console errors`, async ({ page }) => {
    const errors: string[] = [];
    page.on("console", (m) => m.type() === "error" && errors.push(m.text()));
    page.on("pageerror", (e) => errors.push(e.message));
    await page.goto(path);
    await expect(page.locator("h1")).toHaveCount(1);
    await expect(page.locator("main#content")).toBeVisible();
    await page.waitForTimeout(1500);
    // No horizontal page scroll at any viewport.
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
    expect(errors).toEqual([]);
  });
}

test("dashboard shows live cluster data", async ({ page }) => {
  await page.goto("/dashboard");
  await expect(page.getByText(/controller-\d/).first()).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText("streaming")).toBeVisible({ timeout: 10_000 });
});

test("command palette opens with keyboard and navigates", async ({ page, isMobile }) => {
  test.skip(isMobile, "keyboard shortcut is a desktop feature");
  await page.goto("/");
  const input = page.getByRole("combobox", { name: "Search query" });
  // The shortcut listener attaches after hydration, so retry until the dialog opens.
  await expect(async () => {
    if (!(await input.isVisible())) await page.keyboard.press("Control+k");
    await expect(input).toBeVisible({ timeout: 1000 });
  }).toPass({ timeout: 10_000 });
  await input.fill("topology");
  await input.press("Enter");
  await expect(page).toHaveURL(/\/topology$/);
});

test("theme toggle persists across reloads", async ({ page }) => {
  await page.goto("/docs");
  const before = await page.evaluate(() => document.documentElement.dataset.theme);
  await page.getByRole("button", { name: /Switch to (dark|light) theme/ }).click();
  const after = await page.evaluate(() => document.documentElement.dataset.theme);
  expect(after).not.toBe(before);
  await page.reload();
  expect(await page.evaluate(() => document.documentElement.dataset.theme)).toBe(after);
});

test("mobile menu opens and links work", async ({ page, isMobile }) => {
  test.skip(!isMobile, "mobile only");
  await page.goto("/");
  await page.getByRole("button", { name: "Open menu" }).click();
  await page.locator("#mobile-nav").getByRole("link", { name: /Flags/ }).click();
  await expect(page).toHaveURL(/\/flags$/);
});

test("skip link moves focus to content", async ({ page, isMobile }) => {
  test.skip(isMobile, "keyboard only");
  await page.goto("/docs");
  await page.keyboard.press("Tab");
  await expect(page.getByRole("link", { name: "Skip to content" })).toBeFocused();
});

test("start and roll back a rollout from the UI", async ({ page, isMobile }) => {
  test.skip(isMobile, "run once");
  await page.goto("/rollouts");
  await page.getByRole("combobox", { name: /^Flag/ }).selectOption("recs-limit");
  await page.getByRole("combobox", { name: /^Environment/ }).selectOption("staging");
  await page.getByRole("button", { name: "Start rollout" }).click();
  await expect(page.getByText(/Rollout #\d+ started/)).toBeVisible({ timeout: 10_000 });
  await page.getByRole("button", { name: "Roll back", exact: true }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Roll back" }).click();
  await expect(page.getByText(/is now rolled back/)).toBeVisible({ timeout: 10_000 });
});
