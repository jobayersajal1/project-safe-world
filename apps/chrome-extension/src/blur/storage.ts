import { withBlurDefaults, type BlurSettings } from "@safe-world/core";
import { BLUR_SETTINGS_KEY } from "./protocol.js";

export async function getBlurSettings(): Promise<BlurSettings> {
  const raw = await chrome.storage.local.get(BLUR_SETTINGS_KEY);
  return withBlurDefaults(raw[BLUR_SETTINGS_KEY] as Partial<BlurSettings> | undefined);
}

export async function saveBlurSettings(settings: BlurSettings): Promise<void> {
  await chrome.storage.local.set({ [BLUR_SETTINGS_KEY]: settings });
}

export function onBlurSettingsChanged(cb: (settings: BlurSettings) => void): void {
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes[BLUR_SETTINGS_KEY]) {
      cb(withBlurDefaults(changes[BLUR_SETTINGS_KEY].newValue as Partial<BlurSettings>));
    }
  });
}
