import { CATEGORIES, type CategoryId } from "./categories.js";
import type { Settings } from "./types.js";

export function defaultSettings(): Settings {
  const categories = {} as Record<CategoryId, boolean>;
  for (const c of CATEGORIES) categories[c.id] = c.defaultEnabled;
  return {
    enabled: true,
    categories,
    customAllow: [],
    customBlock: [],
    remoteUpdateUrl: "",
    remoteUpdateIntervalHours: 24,
    lastRemoteUpdate: 0,
  };
}

/** Merge stored (possibly partial/older) settings over the current defaults. */
export function withDefaults(stored: Partial<Settings> | undefined | null): Settings {
  const base = defaultSettings();
  if (!stored) return base;
  return {
    ...base,
    ...stored,
    categories: { ...base.categories, ...(stored.categories ?? {}) },
  };
}
