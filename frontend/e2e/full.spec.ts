import { expect, test, type Page } from "@playwright/test";

// Drives every interactive control against the live backend and checks the backend really changed.
test.describe.configure({ mode: "serial" });
test.skip(({ isMobile }) => isMobile, "full interaction suite runs on desktop");

const api = async (page: Page, path: string) => (await page.request.get(`/api${path}`)).json();

// Confirms the open modal by clicking its confirm button.
async function confirm(page: Page, label: string | RegExp) {
  await page.getByRole("dialog").getByRole("button", { name: label }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0, { timeout: 15_000 });
}

test("flags: create, edit config with a rule, preview, evaluate, rename, revisions", async ({ page }) => {
  const key = `e2e-${Date.now() % 100000}`;
  await page.goto("/flags");
  await page.getByRole("button", { name: "New flag" }).click();
  const dlg = page.getByRole("dialog");
  await dlg.getByLabel("Key").fill(key);
  await dlg.getByLabel("Name").fill("E2E flag");
  await dlg.getByLabel("Type").selectOption("string");
  await dlg.getByRole("button", { name: "Create flag" }).click();
  await expect(page.getByText(`Flag ${key} created`)).toBeVisible();
  expect((await api(page, `/flags/${key}`)).type).toBe("string");

  // The new flag is selected; edit its production config.
  await expect(page.getByRole("heading", { name: "E2E flag" })).toBeVisible();
  await page.getByLabel("Enabled in production").check();
  await page.getByLabel("Rollout percentage").fill("30");
  await page.getByRole("button", { name: "Add rule" }).click();
  await page.getByLabel("Rule 1 attribute").fill("country");
  await page.getByLabel("Rule 1 operator").selectOption("in");
  await page.getByLabel("Rule 1 values, comma separated").fill("IN, US");
  await page.getByPlaceholder("Why are you changing this?").fill("e2e edit");
  await page.getByRole("button", { name: "Save as new revision" }).click();
  await expect(page.getByText(/Saved as revision r\d+/)).toBeVisible();
  const cfg = (await api(page, `/flags/${key}`)).envs.production;
  expect(cfg.enabled).toBe(true);
  expect(cfg.rolloutPercent).toBe(30);
  expect(cfg.rules[0]).toMatchObject({ attribute: "country", operator: "in", values: ["IN", "US"] });

  // Preview and evaluate use the same config through the API.
  await page.getByRole("button", { name: "Run preview" }).click();
  await expect(page.getByText(/would get the on variant/)).toBeVisible();
  await page.getByRole("button", { name: "Evaluate" }).click();
  await expect(page.getByText(/RULE_MATCH: rule 1/)).toBeVisible();

  // Rename.
  await page.getByLabel("Name", { exact: true }).last().fill("E2E flag renamed");
  await page.getByRole("button", { name: "Update" }).click();
  await expect(page.getByText("Flag details updated.")).toBeVisible();
  expect((await api(page, `/flags/${key}`)).name).toBe("E2E flag renamed");

  // Revision history shows the change.
  await expect(page.getByRole("cell", { name: "e2e edit" })).toBeVisible();

  // Search filters the list.
  await page.getByPlaceholder("Search by key, name, type or description").fill(key);
  await expect(page.getByRole("button", { name: /E2E flag renamed/ })).toBeVisible();
  await page.getByPlaceholder("Search by key, name, type or description").fill("zzzz-no-match");
  await expect(page.getByText("No flags match")).toBeVisible();
});

test("flags: add environment", async ({ page }) => {
  const env = `qa${Date.now() % 10000}`;
  await page.goto("/flags");
  await page.getByRole("button", { name: "Add environment" }).click();
  const dlg = page.getByRole("dialog");
  await dlg.getByLabel("Key").fill(env);
  await dlg.getByLabel("Name").fill("QA");
  await dlg.getByRole("button", { name: "Create" }).click();
  await expect(page.getByText(`Environment ${env} created`)).toBeVisible();
  const envs = await api(page, "/environments");
  expect(envs.map((e: { key: string }) => e.key)).toContain(env);
  await expect(page.getByLabel("Environment", { exact: true }).locator(`option[value="${env}"]`)).toHaveCount(1);
});

test("rollouts: start, pause, resume, promote, abort; second rollout rolled back", async ({ page }) => {
  // Clean slate for the flag used here.
  for (const r of await api(page, "/rollouts")) {
    if (r.flagKey === "promo-banner" && ["RUNNING", "PAUSED"].includes(r.state)) await page.request.post(`/api/rollouts/${r.id}/abort`, { data: {} });
  }
  await page.goto("/rollouts");
  await page.getByLabel("Flag", { exact: true }).selectOption("promo-banner");
  await page.getByLabel("Environment", { exact: true }).selectOption("production");
  await page.getByLabel("Stages (%)").fill("10, 60, 100");
  await page.getByLabel("Auto-promote").uncheck();
  await page.getByRole("button", { name: "Start rollout" }).click();
  const toast = page.getByText(/Rollout #\d+ started at 10%/);
  await expect(toast).toBeVisible();
  const id = Number((await toast.textContent())!.match(/#(\d+)/)![1]);
  await expect(page).toHaveURL(new RegExp(`id=${id}`));
  expect((await api(page, `/rollouts/${id}`)).state).toBe("RUNNING");

  await page.getByRole("button", { name: "Pause", exact: true }).click();
  await expect.poll(async () => (await api(page, `/rollouts/${id}`)).state).toBe("PAUSED");
  await page.getByRole("button", { name: "Resume", exact: true }).click();
  await expect.poll(async () => (await api(page, `/rollouts/${id}`)).state).toBe("RUNNING");

  await page.getByRole("button", { name: "Promote now" }).click();
  await confirm(page, "Promote");
  await expect.poll(async () => (await api(page, `/rollouts/${id}`)).currentStage).toBe(1);
  expect((await api(page, "/flags/promo-banner")).envs.production.rolloutPercent).toBe(60);

  await page.getByRole("button", { name: "Abort", exact: true }).click();
  await confirm(page, "Abort rollout");
  await expect.poll(async () => (await api(page, `/rollouts/${id}`)).state).toBe("ABORTED");

  // History table lists each transition.
  await expect(page.getByRole("cell", { name: /running → paused/ })).toBeVisible();
  await expect(page.getByRole("cell", { name: /running → aborted/ })).toBeVisible();

  // A second rollout, rolled back by hand, restores the baseline.
  await page.getByRole("button", { name: "Start rollout" }).click();
  const toast2 = page.getByText(/Rollout #\d+ started/).last();
  await expect(toast2).toBeVisible();
  const id2 = Number((await toast2.textContent())!.match(/#(\d+)/)![1]);
  await expect(page).toHaveURL(new RegExp(`id=${id2}`));
  await page.getByRole("button", { name: "Roll back", exact: true }).click();
  await confirm(page, "Roll back");
  await expect.poll(async () => (await api(page, `/rollouts/${id2}`)).state).toBe("ROLLED_BACK");

  // Selecting another rollout from the list works.
  await page.getByRole("button", { name: new RegExp(`#${id} promo-banner`) }).click();
  await expect(page.getByRole("heading", { name: new RegExp(`Rollout #${id}:`) })).toBeVisible();
});

test("agents: per-agent fault, bulk fault, clear, traffic, controller isolate", async ({ page }) => {
  await page.goto("/agents");
  const agents = await api(page, "/agents");
  const agent = agents[0].id as string;

  // Per-agent delay fault.
  const row = page.getByRole("row", { name: new RegExp(agent) });
  await row.getByLabel(`Fault for ${agent}`).selectOption("delay");
  await row.getByRole("button", { name: "Go" }).click();
  await confirm(page, "Inject");
  await expect.poll(async () => (await api(page, "/agents")).find((a: { id: string }) => a.id === agent).activeFaults.join(","), { timeout: 10_000 }).toContain("delay");

  // Bulk: clear everything.
  await page.getByRole("radio", { name: "Clear faults" }).click();
  await page.getByRole("button", { name: "Apply to all agents" }).click();
  await confirm(page, "Inject");
  await expect.poll(async () => (await api(page, "/agents")).every((a: { activeFaults: string[] }) => a.activeFaults.length === 0), { timeout: 10_000 }).toBe(true);

  // Traffic rate.
  await page.getByLabel("Requests/s per agent").fill("55");
  await page.getByRole("button", { name: "Apply", exact: true }).click();
  await expect(page.getByText("Traffic on at 55 req/s per agent.")).toBeVisible();
  expect((await api(page, "/traffic")).rps).toBe(55);
  await page.getByLabel("Requests/s per agent").fill("40");
  await page.getByRole("button", { name: "Apply", exact: true }).click();
  await expect.poll(async () => (await api(page, "/traffic")).rps).toBe(40);

  // Isolate a follower controller (does not disturb the leader).
  const ov = await api(page, "/overview");
  const follower = ov.controllers.find((c: { isLeader: boolean; alive: boolean }) => !c.isLeader && c.alive).id as string;
  await page.getByRole("row", { name: new RegExp(follower) }).getByRole("button", { name: "Isolate 15 s" }).click();
  await confirm(page, "Isolate");
  const ev = await api(page, "/events?type=FAULT_INJECTED&limit=5");
  expect(ev.some((e: { data: { target: string; action: string } }) => e.data.target === follower && e.data.action === "isolate")).toBe(true);
});

test("topology: selecting a node shows its details", async ({ page }) => {
  await page.goto("/topology");
  await page.getByRole("button", { name: /^controller-1/ }).click();
  await expect(page.getByRole("heading", { name: "controller-1" })).toBeVisible();
  await expect(page.getByText(/→ controller-1-state/)).toBeVisible();
});

test("experiments: lease race, deadlock, snapshot, clocks compare, scenario run and replay", async ({ page }) => {
  test.setTimeout(150_000);
  await page.goto("/experiments");

  await page.getByRole("button", { name: "Run lease race" }).click();
  await expect(page.getByText(/stale write rejected/).first()).toBeVisible({ timeout: 20_000 });

  await page.getByLabel("Deadlock scenario").selectOption("three-way");
  await page.getByRole("button", { name: "Run", exact: true }).click();
  await expect(page.getByText(/found after/)).toBeVisible({ timeout: 25_000 });
  await page.getByLabel("Deadlock scenario").selectOption("ordered");
  await page.getByRole("button", { name: "Run", exact: true }).click();
  await expect(page.getByText(/No cycle formed/)).toBeVisible({ timeout: 25_000 });

  await page.getByRole("button", { name: "Take snapshot" }).click();
  await expect(page.getByText(/^snap-\d+$/).first()).toBeVisible({ timeout: 15_000 });
  await expect(page.getByRole("cell", { name: /agent-/ }).first()).toBeVisible();

  // Clocks: pick two events and compare.
  await page.locator("#clocks").getByRole("button", { name: "Refresh" }).click();
  const boxes = page.locator("#clocks input[type=checkbox]");
  await boxes.nth(0).check();
  await boxes.nth(1).check();
  await page.getByRole("button", { name: /Compare selected \(2\/2\)/ }).click();
  await expect(page.locator("#clocks").getByText(/^(before|after|equal|concurrent)$/)).toBeVisible();

  // Run a scenario and wait for it to finish, then replay it.
  await page.getByLabel("Fault scenario").selectOption("message-delay");
  await page.getByRole("button", { name: "Run scenario" }).click();
  await expect(page.getByText(/Experiment #\d+ started/)).toBeVisible();
  await expect.poll(async () => (await api(page, "/experiments/runs"))[0].status, { timeout: 60_000, intervals: [2000] }).toBe("COMPLETED");
  const first = (await api(page, "/experiments/runs"))[0];
  expect(first.result.delayedConvergenceMs).toBeGreaterThan(first.result.baselineConvergenceMs);
  await page.getByRole("button", { name: "Replay" }).first().click();
  await expect.poll(async () => (await api(page, "/experiments/runs"))[0].id, { timeout: 10_000 }).toBeGreaterThan(first.id);
  await expect.poll(async () => (await api(page, "/experiments/runs"))[0].status, { timeout: 60_000, intervals: [2000] }).toBe("COMPLETED");
  await page.getByLabel("Scenario to compare").selectOption("message-delay");
  await expect(page.getByLabel("Delayed convergence").getByRole("listitem")).toHaveCount(2, { timeout: 10_000 }).catch(async () => {
    // Older runs of the same scenario may also be present.
    expect(await page.getByLabel("Delayed convergence").getByRole("listitem").count()).toBeGreaterThanOrEqual(2);
  });
});

test("global: help panel, privacy notice, event filters, back to top", async ({ page }) => {
  await page.goto("/dashboard");
  await page.getByRole("button", { name: "Got it" }).click();
  await expect(page.getByRole("region", { name: "Privacy notice" })).toHaveCount(0);
  await page.reload();
  await expect(page.getByRole("region", { name: "Privacy notice" })).toHaveCount(0);

  await page.getByRole("button", { name: "Help" }).click();
  await expect(page.getByRole("dialog", { name: "Help and feedback" })).toBeVisible();
  await page.getByRole("button", { name: "Close help" }).click();

  await page.getByRole("button", { name: "Elections" }).click();
  await expect(page.getByRole("button", { name: "Elections" })).toHaveAttribute("aria-pressed", "true");

  await page.mouse.wheel(0, 4000);
  await page.getByRole("button", { name: "Back to top" }).click();
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBeLessThan(50);
});

test("crash buttons: agent and controller processes die and are restarted", async ({ page }) => {
  test.setTimeout(120_000);
  await page.goto("/agents");
  const agent = (await api(page, "/agents"))[0];
  const row = page.getByRole("row", { name: new RegExp(agent.id) });
  await row.getByLabel(`Fault for ${agent.id}`).selectOption("crash");
  await row.getByRole("button", { name: "Go" }).click();
  await confirm(page, "Inject");
  // The restarted agent registers with a new incarnation.
  await expect.poll(async () => (await api(page, "/agents")).find((a: { id: string }) => a.id === agent.id)?.incarnation,
    { timeout: 40_000, intervals: [1000] }).not.toBe(agent.incarnation);

  const ov = await api(page, "/overview");
  const follower = ov.controllers.find((c: { isLeader: boolean; alive: boolean }) => !c.isLeader && c.alive);
  await page.getByRole("row", { name: new RegExp(follower.id) }).getByRole("button", { name: "Crash" }).click();
  await confirm(page, "Crash process");
  await expect.poll(async () => (await api(page, "/controllers")).find((c: { id: string }) => c.id === follower.id).startedAt,
    { timeout: 60_000, intervals: [1000] }).not.toBe(follower.startedAt);
  await expect.poll(async () => (await api(page, "/controllers")).find((c: { id: string }) => c.id === follower.id).alive,
    { timeout: 30_000, intervals: [1000] }).toBe(true);
});
