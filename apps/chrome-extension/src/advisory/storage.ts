import {
  ADVISORY_CATEGORIES,
  ADVISORY_STORAGE_KEY,
  loadDomainModel,
  withAdvisoryDefaults,
  type AdvisorySettings,
  type DomainModel,
  type SerializedDomainModel,
} from "@safe-world/core";

export async function getAdvisorySettings(): Promise<AdvisorySettings> {
  const raw = await chrome.storage.local.get(ADVISORY_STORAGE_KEY);
  return withAdvisoryDefaults(raw[ADVISORY_STORAGE_KEY] as Partial<AdvisorySettings> | undefined);
}

export async function saveAdvisorySettings(settings: AdvisorySettings): Promise<void> {
  await chrome.storage.local.set({ [ADVISORY_STORAGE_KEY]: settings });
}

export function onAdvisorySettingsChanged(cb: (settings: AdvisorySettings) => void): void {
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes[ADVISORY_STORAGE_KEY]) {
      cb(withAdvisoryDefaults(changes[ADVISORY_STORAGE_KEY].newValue as Partial<AdvisorySettings>));
    }
  });
}

let cached: Promise<DomainModel[]> | null = null;

/**
 * The models, fetched as packaged files rather than imported.
 *
 * Each is 341 KB of base64, and `import`ing them would inline both into the
 * service worker's bundle — the same reason `loadCategoryDomains` fetches the
 * blocklists instead of importing them. Fetched once and held: the worker is
 * torn down when idle, and re-parsing 682 KB on every wake would put that cost
 * on the first navigation after every idle period.
 *
 * A missing file is not an error worth surfacing. The models are a generated
 * artifact, and a build without them should behave exactly like a build from
 * before this feature existed rather than logging on every page load.
 */
export async function loadAdvisoryModels(): Promise<DomainModel[]> {
  cached ??= (async () => {
    const out: DomainModel[] = [];
    for (const category of ADVISORY_CATEGORIES) {
      try {
        const res = await fetch(chrome.runtime.getURL(`model/${category}.json`));
        if (!res.ok) continue;
        out.push(loadDomainModel((await res.json()) as SerializedDomainModel));
      } catch {
        // Absent or unreadable — this category simply has no advisory.
      }
    }
    return out;
  })();
  return cached;
}
