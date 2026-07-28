import { CATEGORIES } from "@safe-world/core";
import { getSettings, getTodayStats, updateSettings } from "../storage.js";

const master = document.getElementById("master") as HTMLInputElement;
const categoriesEl = document.getElementById("categories")!;
const blockedTodayEl = document.getElementById("blockedToday")!;

async function render(): Promise<void> {
  const [settings, stats] = await Promise.all([getSettings(), getTodayStats()]);
  master.checked = settings.enabled;
  blockedTodayEl.textContent = String(stats.blocked);

  categoriesEl.replaceChildren();
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
    input.disabled = !settings.enabled;
    input.addEventListener("change", async () => {
      const cur = await getSettings();
      await updateSettings({ categories: { ...cur.categories, [c.id]: input.checked } });
    });
    const slider = document.createElement("span");
    slider.className = "slider";
    sw.append(input, slider);

    row.append(label, sw);
    categoriesEl.append(row);
  }
}

master.addEventListener("change", async () => {
  await updateSettings({ enabled: master.checked });
  await render();
});

document.getElementById("openOptions")!.addEventListener("click", () => {
  chrome.runtime.openOptionsPage();
});

void render();
