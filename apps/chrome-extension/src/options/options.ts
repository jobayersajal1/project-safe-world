import { CATEGORIES } from "@safe-world/core";
import { getSettings, saveSettings } from "../storage.js";

const categoriesEl = document.getElementById("categories")!;
const allowEl = document.getElementById("allow") as HTMLTextAreaElement;
const blockEl = document.getElementById("block") as HTMLTextAreaElement;
const remoteUrlEl = document.getElementById("remoteUrl") as HTMLInputElement;
const intervalEl = document.getElementById("interval") as HTMLInputElement;
const lastUpdateEl = document.getElementById("lastUpdate")!;
const updateStatusEl = document.getElementById("updateStatus")!;
const savedEl = document.getElementById("saved")!;

const categoryToggles = new Map<string, HTMLInputElement>();

function parseList(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((s) => s.trim())
    .filter(Boolean);
}

async function render(): Promise<void> {
  const settings = await getSettings();

  categoriesEl.replaceChildren();
  categoryToggles.clear();
  for (const c of CATEGORIES) {
    const row = document.createElement("div");
    row.className = "row";
    const label = document.createElement("div");
    label.innerHTML = `<div>${c.label}</div><div class="muted" style="font-size:12px">${c.description}</div>`;
    const sw = document.createElement("label");
    sw.className = "switch";
    const input = document.createElement("input");
    input.type = "checkbox";
    input.checked = settings.categories[c.id];
    const slider = document.createElement("span");
    slider.className = "slider";
    sw.append(input, slider);
    row.append(label, sw);
    categoriesEl.append(row);
    categoryToggles.set(c.id, input);
  }

  allowEl.value = settings.customAllow.join("\n");
  blockEl.value = settings.customBlock.join("\n");
  remoteUrlEl.value = settings.remoteUpdateUrl;
  intervalEl.value = String(settings.remoteUpdateIntervalHours);
  lastUpdateEl.textContent = settings.lastRemoteUpdate
    ? `Last updated: ${new Date(settings.lastRemoteUpdate).toLocaleString()}`
    : "Never updated remotely.";
}

document.getElementById("save")!.addEventListener("click", async () => {
  const settings = await getSettings();
  const categories = { ...settings.categories };
  for (const [id, input] of categoryToggles) categories[id as keyof typeof categories] = input.checked;

  await saveSettings({
    ...settings,
    categories,
    customAllow: parseList(allowEl.value),
    customBlock: parseList(blockEl.value),
    remoteUpdateUrl: remoteUrlEl.value.trim(),
    remoteUpdateIntervalHours: Math.max(1, Number(intervalEl.value) || 24),
  });

  savedEl.hidden = false;
  setTimeout(() => (savedEl.hidden = true), 1500);
});

document.getElementById("updateNow")!.addEventListener("click", () => {
  updateStatusEl.textContent = "Updating…";
  chrome.runtime.sendMessage({ type: "runRemoteUpdate" }, (res: { ok: boolean; error?: string }) => {
    updateStatusEl.textContent = res?.ok ? "Updated ✓" : `Failed: ${res?.error ?? "unknown error"}`;
    void render();
  });
});

void render();
