import {
  CATEGORIES,
  SCRAMBLE_FORMAT,
  generateRulesForCategory,
  normalizeHost,
  plainDomains,
  unscrambleDomain,
  type CategoryId,
  type DNRRule,
  type RemoteUpdatePayload,
  type Settings,
} from "@safe-world/core";
import { getSettings, incrementBlockedToday, onSettingsChanged } from "./storage.js";

const BLOCKED_PAGE = "/src/blocked/blocked.html";
const CUSTOM_BLOCK_BASE = 900_000_000;
const CUSTOM_ALLOW_BASE = 910_000_000;

/**
 * Category rules live below this id; custom allow/block rules live above it.
 * Both are dynamic rules, so rebuilding one must not wipe the other.
 */
const CUSTOM_RULE_FLOOR = CUSTOM_BLOCK_BASE;

/** In-memory map of the last top-level navigation target per tab, for the block page. */
const pendingNavigation = new Map<number, string>();

chrome.webNavigation.onBeforeNavigate.addListener((details) => {
  if (details.frameId === 0 && details.tabId >= 0) {
    pendingNavigation.set(details.tabId, details.url);
  }
});

chrome.tabs.onRemoved.addListener((tabId) => pendingNavigation.delete(tabId));

/**
 * The bundled list for a category, unscrambled.
 *
 * The lists ship scrambled so an unpacked `.zip` isn't a readable directory of
 * blocked sites, and they're fetched as packaged files rather than imported so
 * ~85k base64 entries don't get inlined into this worker's bundle.
 */
async function loadCategoryDomains(id: CategoryId): Promise<string[]> {
  const res = await fetch(chrome.runtime.getURL(`lists/${id}.json`));
  if (!res.ok) throw new Error(`bundled list ${id} missing (HTTP ${res.status})`);
  const file = (await res.json()) as { format?: string; domains: string[] };

  // Fail loudly on a format this build can't reverse. Returning [] would leave
  // an extension that looks healthy and blocks nothing — the worst failure mode
  // for this project, and the reason plainDomains() throws too.
  if (file.format !== SCRAMBLE_FORMAT) {
    throw new Error(`bundled list ${id}: unknown format "${file.format}"`);
  }
  return file.domains.map(unscrambleDomain).filter(Boolean);
}

/**
 * Rebuild the category rules to match the user's settings.
 *
 * These used to be static rulesets toggled with `updateEnabledRulesets`, but a
 * static ruleset is a file of literal domains inside the extension — shipping
 * one would publish the whole list to anyone who unzips it. Dynamic rules match
 * identically and persist across restarts; the cost is that they have to be
 * rebuilt here rather than declared in the manifest.
 */
async function syncCategoryRules(settings: Settings): Promise<void> {
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  const removeRuleIds = existing.filter((r) => r.id < CUSTOM_RULE_FLOOR).map((r) => r.id);

  const addRules: DNRRule[] = [];
  if (settings.enabled) {
    for (const c of CATEGORIES) {
      if (!settings.categories[c.id]) continue;
      const domains = await loadCategoryDomains(c.id);
      if (domains.length === 0) continue;
      addRules.push(...generateRulesForCategory(c.id, domains, { blockedPagePath: BLOCKED_PAGE }));
    }
  }

  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds,
    addRules: addRules as chrome.declarativeNetRequest.Rule[],
  });
}

/** Rebuild the dynamic rules that encode the user's custom allow/block lists. */
async function syncCustomRules(settings: Settings): Promise<void> {
  const existing = await chrome.declarativeNetRequest.getDynamicRules();
  const removeRuleIds = existing
    .filter((r) => r.id >= CUSTOM_RULE_FLOOR)
    .map((r) => r.id);

  const addRules: DNRRule[] = [];

  if (settings.enabled) {
    // Allow rules win via higher priority.
    settings.customAllow.map(normalizeHost).filter(Boolean).forEach((domain, i) => {
      addRules.push({
        id: CUSTOM_ALLOW_BASE + i,
        priority: 100,
        action: { type: "allow" },
        condition: { requestDomains: [domain] },
      });
    });
    settings.customBlock.map(normalizeHost).filter(Boolean).forEach((domain, i) => {
      addRules.push({
        id: CUSTOM_BLOCK_BASE + i,
        priority: 50,
        action: { type: "redirect", redirect: { extensionPath: `${BLOCKED_PAGE}?category=custom` } },
        condition: { requestDomains: [domain], resourceTypes: ["main_frame"] },
      });
      addRules.push({
        id: CUSTOM_BLOCK_BASE + 500_000 + i,
        priority: 50,
        action: { type: "block" },
        condition: {
          requestDomains: [domain],
          resourceTypes: ["sub_frame", "script", "image", "stylesheet", "xmlhttprequest", "media", "font", "object", "ping", "websocket", "other"],
        },
      });
    });
  }

  await chrome.declarativeNetRequest.updateDynamicRules({
    removeRuleIds,
    addRules: addRules as chrome.declarativeNetRequest.Rule[],
  });
}

async function syncAll(settings: Settings): Promise<void> {
  // Sequential, not Promise.all: both call updateDynamicRules, and concurrent
  // writes to the same rule store race — one read-modify-write can land on a
  // snapshot the other has already replaced.
  await syncCategoryRules(settings);
  await syncCustomRules(settings);
}

/** Fetch the remote update payload and apply it as session rules per category. */
async function runRemoteUpdate(settings: Settings): Promise<{ ok: boolean; error?: string }> {
  if (!settings.remoteUpdateUrl) return { ok: false, error: "No remote update URL configured." };
  try {
    const res = await fetch(settings.remoteUpdateUrl, { cache: "no-store" });
    if (!res.ok) return { ok: false, error: `HTTP ${res.status}` };
    const payload = (await res.json()) as RemoteUpdatePayload;

    // Already applied this exact set — rebuilding identical session rules is
    // pure work. A payload with no updateId always applies, which is how older
    // feeds behaved. Checked before decoding, since unscrambling is the
    // expensive part.
    const { lastAppliedUpdateId } = await chrome.storage.local.get("lastAppliedUpdateId");
    if (payload.updateId && payload.updateId === lastAppliedUpdateId) {
      settings.lastRemoteUpdate = Date.now();
      await chrome.storage.local.set({ settings });
      return { ok: true };
    }
    // The published list is scrambled so the public URL isn't a readable
    // directory of blocked sites; declarativeNetRequest needs literal domains,
    // so unscramble before building rules. Throws on a format this platform
    // can't use, which surfaces as an error rather than silently blocking
    // nothing.
    const decoded = plainDomains(payload);

    const addRules: DNRRule[] = [];
    for (const c of CATEGORIES) {
      const domains = decoded[c.id];
      if (!domains?.length) continue;
      // Offset remote rule ids past the static range for the same category.
      const rules = generateRulesForCategory(c.id, domains, { blockedPagePath: BLOCKED_PAGE });
      for (const r of rules) addRules.push({ ...r, id: r.id + 500_000 });
    }

    const existing = await chrome.declarativeNetRequest.getSessionRules();
    await chrome.declarativeNetRequest.updateSessionRules({
      removeRuleIds: existing.map((r) => r.id),
      addRules: addRules as chrome.declarativeNetRequest.Rule[],
    });

    settings.lastRemoteUpdate = Date.now();
    await chrome.storage.local.set({ settings });
    if (payload.updateId) {
      await chrome.storage.local.set({ lastAppliedUpdateId: payload.updateId });
    }
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}

const REMOTE_ALARM = "safe-world-remote-update";

async function scheduleRemoteUpdate(settings: Settings): Promise<void> {
  await chrome.alarms.clear(REMOTE_ALARM);
  if (settings.remoteUpdateUrl && settings.remoteUpdateIntervalHours > 0) {
    chrome.alarms.create(REMOTE_ALARM, {
      periodInMinutes: settings.remoteUpdateIntervalHours * 60,
    });
  }
}

chrome.alarms.onAlarm.addListener(async (alarm) => {
  if (alarm.name === REMOTE_ALARM) {
    await runRemoteUpdate(await getSettings());
  }
});

async function init(): Promise<void> {
  const settings = await getSettings();
  await syncAll(settings);
  await scheduleRemoteUpdate(settings);
}

chrome.runtime.onInstalled.addListener(init);
chrome.runtime.onStartup.addListener(init);

onSettingsChanged(async (settings) => {
  await syncAll(settings);
  await scheduleRemoteUpdate(settings);
});

chrome.runtime.onMessage.addListener((msg, sender, sendResponse) => {
  (async () => {
    switch (msg?.type) {
      case "getBlockedInfo": {
        const tabId = sender.tab?.id;
        const url = tabId != null ? pendingNavigation.get(tabId) : undefined;
        const blocked = await incrementBlockedToday();
        sendResponse({ host: url ? normalizeHost(url) : "", url: url ?? "", blockedToday: blocked });
        break;
      }
      case "runRemoteUpdate": {
        sendResponse(await runRemoteUpdate(await getSettings()));
        break;
      }
      case "allowOnce": {
        const host = normalizeHost(msg.host ?? "");
        if (!host) { sendResponse({ ok: false }); break; }
        const id = CUSTOM_ALLOW_BASE + 800_000 + (Math.floor(Math.random() * 100_000));
        await chrome.declarativeNetRequest.updateSessionRules({
          addRules: [{
            id,
            priority: 200,
            action: { type: "allow" },
            condition: { requestDomains: [host] },
          }] as chrome.declarativeNetRequest.Rule[],
        });
        sendResponse({ ok: true });
        break;
      }
      case "allowAlways": {
        const host = normalizeHost(msg.host ?? "");
        if (!host) { sendResponse({ ok: false }); break; }
        const settings = await getSettings();
        if (!settings.customAllow.includes(host)) {
          settings.customAllow.push(host);
          await chrome.storage.local.set({ settings });
        }
        sendResponse({ ok: true });
        break;
      }
      default:
        sendResponse({ error: "unknown message" });
    }
  })();
  return true; // async response
});
