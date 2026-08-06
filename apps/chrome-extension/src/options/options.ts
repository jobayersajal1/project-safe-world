import { CATEGORIES, type BlurRevealMode, type BlurTarget } from "@safe-world/core";
import { getSettings, saveSettings } from "../storage.js";
import { getBlurSettings, saveBlurSettings } from "../blur/storage.js";
import { getAdvisorySettings, saveAdvisorySettings } from "../advisory/storage.js";

const categoriesEl = document.getElementById("categories")!;
const allowEl = document.getElementById("allow") as HTMLTextAreaElement;
const blockEl = document.getElementById("block") as HTMLTextAreaElement;
const remoteUrlEl = document.getElementById("remoteUrl") as HTMLInputElement;
const intervalEl = document.getElementById("interval") as HTMLInputElement;
const lastUpdateEl = document.getElementById("lastUpdate")!;
const updateStatusEl = document.getElementById("updateStatus")!;
const savedEl = document.getElementById("saved")!;

const blurEnabledEl = document.getElementById("blurEnabled") as HTMLInputElement;
const blurTargetEl = document.getElementById("blurTarget") as HTMLSelectElement;
const blurStrengthEl = document.getElementById("blurStrength") as HTMLInputElement;
const blurMinPxEl = document.getElementById("blurMinPx") as HTMLInputElement;
const blurVideoEl = document.getElementById("blurVideo") as HTMLInputElement;
const blurRevealEl = document.getElementById("blurReveal") as HTMLSelectElement;
const blurOptionsEl = document.getElementById("blurOptions")!;
const advisoryEnabledEl = document.getElementById("advisoryEnabled") as HTMLInputElement;
const advisoryList2El = document.getElementById("advisoryList2") as HTMLInputElement;
const advisoryList3El = document.getElementById("advisoryList3") as HTMLInputElement;
const advisoryBlockEl = document.getElementById("advisoryBlock") as HTMLInputElement;
const advisoryOptionsEl = document.getElementById("advisoryOptions")!;

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

  const advisory = await getAdvisorySettings();
  advisoryEnabledEl.checked = advisory.enabled;
  advisoryList2El.checked = advisory.categories.list2 === true;
  advisoryList3El.checked = advisory.categories.list3 === true;
  advisoryBlockEl.checked = advisory.blockConfident;
  advisoryOptionsEl.hidden = !advisory.enabled;

  const blur = await getBlurSettings();
  blurEnabledEl.checked = blur.enabled;
  blurTargetEl.value = blur.target;
  blurStrengthEl.value = String(blur.strength);
  blurMinPxEl.value = String(blur.minImagePx);
  blurVideoEl.checked = blur.blurVideo;
  blurRevealEl.value = blur.revealMode;
  blurOptionsEl.hidden = !blur.enabled;

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

  // Its own key too, for the same reason as the blur settings below.
  await saveAdvisorySettings({
    enabled: advisoryEnabledEl.checked,
    categories: { list2: advisoryList2El.checked, list3: advisoryList3El.checked },
    blockConfident: advisoryBlockEl.checked,
  });

  // Stored under its own key, never merged into `settings`. See
  // packages/core/src/blur.ts for why that separation is load-bearing.
  await saveBlurSettings({
    enabled: blurEnabledEl.checked,
    target: blurTargetEl.value as BlurTarget,
    strength: clamp(Number(blurStrengthEl.value), 4, 60, 16),
    minImagePx: clamp(Number(blurMinPxEl.value), 16, 512, 64),
    blurVideo: blurVideoEl.checked,
    revealMode: blurRevealEl.value as BlurRevealMode,
  });

  savedEl.hidden = false;
  setTimeout(() => (savedEl.hidden = true), 1500);
});

function clamp(value: number, min: number, max: number, fallback: number): number {
  if (!Number.isFinite(value)) return fallback;
  return Math.min(max, Math.max(min, Math.round(value)));
}

// Reveal the rest of the section as soon as the switch flips, rather than after
// a save — the settings below it are meaningless while it is off, and hiding
// them until a round trip makes the switch feel unresponsive.
advisoryEnabledEl.addEventListener("change", () => {
  advisoryOptionsEl.hidden = !advisoryEnabledEl.checked;
});

blurEnabledEl.addEventListener("change", () => {
  blurOptionsEl.hidden = !blurEnabledEl.checked;
});

document.getElementById("updateNow")!.addEventListener("click", () => {
  updateStatusEl.textContent = "Updating…";
  chrome.runtime.sendMessage({ type: "runRemoteUpdate" }, (res: { ok: boolean; error?: string }) => {
    updateStatusEl.textContent = res?.ok ? "Updated ✓" : `Failed: ${res?.error ?? "unknown error"}`;
    void render();
  });
});

void render();
