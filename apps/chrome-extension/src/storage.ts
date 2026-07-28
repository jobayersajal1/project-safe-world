import { withDefaults, type Settings, type DailyStats } from "@safe-world/core";

const SETTINGS_KEY = "settings";
const STATS_KEY = "stats";

export async function getSettings(): Promise<Settings> {
  const raw = await chrome.storage.local.get(SETTINGS_KEY);
  return withDefaults(raw[SETTINGS_KEY]);
}

export async function saveSettings(settings: Settings): Promise<void> {
  await chrome.storage.local.set({ [SETTINGS_KEY]: settings });
}

export async function updateSettings(patch: Partial<Settings>): Promise<Settings> {
  const next = { ...(await getSettings()), ...patch };
  await saveSettings(next);
  return next;
}

function todayKey(): string {
  // Local YYYY-MM-DD.
  const d = new Date();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${d.getFullYear()}-${m}-${day}`;
}

export async function getTodayStats(): Promise<DailyStats> {
  const raw = await chrome.storage.local.get(STATS_KEY);
  const stored = raw[STATS_KEY] as DailyStats | undefined;
  const today = todayKey();
  if (!stored || stored.date !== today) return { date: today, blocked: 0 };
  return stored;
}

export async function incrementBlockedToday(): Promise<number> {
  const stats = await getTodayStats();
  const next: DailyStats = { date: stats.date, blocked: stats.blocked + 1 };
  await chrome.storage.local.set({ [STATS_KEY]: next });
  return next.blocked;
}

export function onSettingsChanged(cb: (settings: Settings) => void): void {
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes[SETTINGS_KEY]) {
      cb(withDefaults(changes[SETTINGS_KEY].newValue as Partial<Settings>));
    }
  });
}
