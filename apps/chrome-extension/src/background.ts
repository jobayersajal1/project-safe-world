import {
  AdvisoryCache,
  CATEGORIES,
  SCRAMBLE_FORMAT,
  advise,
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
import { getBlurSettings, onBlurSettingsChanged } from "./blur/storage.js";
import { getAdvisorySettings, loadAdvisoryModels, onAdvisorySettingsChanged } from "./advisory/storage.js";
import {
  BLUR_CSS_PATH,
  BLUR_CSS_SCRIPT_ID,
  OFFSCREEN_PATH,
  type BlurVerdictResponse,
  type OffscreenMessage,
} from "./blur/protocol.js";
import type { BlurSettings } from "@safe-world/core";

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

/**
 * The advisory second stage.
 *
 * `declarativeNetRequest` cannot express it — a DNR rule is a fixed list of
 * domains, and the whole point here is a host nobody has listed. So the check
 * rides on navigation instead, and sends the tab to the same blocked page with
 * `advisory=1`, where "continue anyway" is the expected answer rather than the
 * dangerous one.
 *
 * The gap this leaves is real and is the right trade: MV3 has no blocking
 * navigation hook, so the page has already started loading when we redirect.
 * A model *guess* is not worth the alternative — a blocking request handler
 * over every navigation on the device — and the listed categories, which are
 * the protection promise, never take this path at all. They are blocked by DNR
 * before a request leaves.
 */
const advisoryCache = new AdvisoryCache();

async function checkAdvisory(tabId: number, url: string): Promise<void> {
  const settings = await getAdvisorySettings();
  if (!settings.enabled) return;

  const host = normalizeHost(url);
  if (!host) return;

  // Anything the lists or the user's own rules already cover is not our
  // business: DNR has either blocked it, or the user explicitly allowed it and
  // a guess must not override that.
  const main = await getSettings();
  if (main.customAllow.some((d) => host === d || host.endsWith("." + d))) return;

  const models = await loadAdvisoryModels();
  if (models.length === 0) return;

  const verdict = advisoryCache.get(host, () =>
    advise(host, models, settings, { allowBlocking: settings.blockConfident }),
  );
  if (!verdict.advise) return;

  // A model block still reaches the blocked page rather than a dead tab: it is
  // a guess, so "always allow this site" has to stay one click away.
  const advisory = verdict.action === "warn" ? "&advisory=1" : "&advisory=strict";
  const target = `${BLOCKED_PAGE}?category=${verdict.category}${advisory}`;
  await chrome.tabs.update(tabId, { url: chrome.runtime.getURL(target) }).catch(() => {});
}

chrome.webNavigation.onBeforeNavigate.addListener((details) => {
  if (details.frameId === 0 && details.tabId >= 0) {
    pendingNavigation.set(details.tabId, details.url);
    if (details.url.startsWith("http")) {
      void checkAdvisory(details.tabId, details.url);
    }
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

/* ---------------------------------------------------------------------- blur */

/**
 * Creating the offscreen document is not idempotent — a second call while the
 * first is still in flight throws "Only a single offscreen document may be
 * created". A page of images asks fifty times at once, so the promise is shared.
 */
let offscreenReady: Promise<void> | null = null;

async function ensureOffscreen(): Promise<void> {
  offscreenReady ??= (async () => {
    if (await chrome.offscreen.hasDocument()) return;
    await chrome.offscreen.createDocument({
      url: OFFSCREEN_PATH,
      // `BLOBS` is the honest reason: the detector fetches each image itself and
      // decodes it with `createImageBitmap`, because drawing a third-party <img>
      // to a canvas taints it and every read after that throws.
      reasons: [chrome.offscreen.Reason.BLOBS],
      justification:
        "Runs the on-device face and gender models used to blur photos of people. No image leaves the machine.",
    });
  })().catch((e) => {
    // Let the next request try again rather than wedging the feature on one
    // failed creation.
    offscreenReady = null;
    throw e;
  });
  return offscreenReady;
}

/**
 * Inject or withdraw the boot stylesheet.
 *
 * This is registered dynamically rather than declared in the manifest for one
 * reason: a manifest content-style is injected into every page unconditionally,
 * and this feature ships off. Every user who never enables it would watch every
 * image flash blurred while an async settings read came back. Registering the
 * sheet only while the feature is on removes the race instead of shortening it.
 */
async function syncBlurStyles(blur: BlurSettings): Promise<void> {
  const registered = await chrome.scripting.getRegisteredContentScripts({
    ids: [BLUR_CSS_SCRIPT_ID],
  });

  if (!blur.enabled) {
    if (registered.length > 0) {
      await chrome.scripting.unregisterContentScripts({ ids: [BLUR_CSS_SCRIPT_ID] });
    }
    // Withdrawing the registration does nothing to pages it was already
    // injected into; the content script overrides it there on the same
    // storage change.
    return;
  }

  if (registered.length === 0) {
    await chrome.scripting.registerContentScripts([
      {
        id: BLUR_CSS_SCRIPT_ID,
        matches: ["http://*/*", "https://*/*"],
        css: [BLUR_CSS_PATH],
        runAt: "document_start",
        allFrames: true,
        persistAcrossSessions: true,
      },
    ]);
  }

  // Registration only affects pages loaded from now on, so tabs that are
  // already open get the sheet directly — otherwise enabling the feature
  // appears to do nothing until every tab is reloaded.
  const tabs = await chrome.tabs.query({ url: ["http://*/*", "https://*/*"] });
  await Promise.all(
    tabs.map((t) =>
      t.id == null
        ? Promise.resolve()
        : chrome.scripting
            .insertCSS({ target: { tabId: t.id, allFrames: true }, files: [BLUR_CSS_PATH] })
            .catch(() => {
              // Tabs we have no access to (the web store, other extensions'
              // pages) are expected to refuse. Nothing to do and nothing wrong.
            }),
    ),
  );
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
  await syncBlurStyles(await getBlurSettings());
}

chrome.runtime.onInstalled.addListener(init);
chrome.runtime.onStartup.addListener(init);

onSettingsChanged(async (settings) => {
  await syncAll(settings);
  await scheduleRemoteUpdate(settings);
  // A host the user has just allowed must stop being advised against
  // immediately, and the cache would otherwise hold the old verdict.
  advisoryCache.clear();
});

onAdvisorySettingsChanged(() => advisoryCache.clear());

onBlurSettingsChanged(async (blur) => {
  await syncBlurStyles(blur);
  // Every cached verdict was reached under the old target, so none of them can
  // be trusted after a change. Only worth saying if the document is up.
  if (await chrome.offscreen.hasDocument()) {
    const msg: OffscreenMessage = { to: "offscreen", type: "blurClearCache" };
    await chrome.runtime.sendMessage(msg).catch(() => {});
  }
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
      // Registered here deliberately: this switch `default`s to an error, so a
      // message type that is not listed does not misbehave — it silently never
      // works, and every image on every page stays blurred forever.
      case "blurCheck": {
        const url = typeof msg.url === "string" ? msg.url : "";
        if (!url) { sendResponse({ verdict: "blur" } satisfies BlurVerdictResponse); break; }

        const blur = await getBlurSettings();
        if (!blur.enabled) { sendResponse({ verdict: "clear" } satisfies BlurVerdictResponse); break; }

        try {
          await ensureOffscreen();
          const req: OffscreenMessage = {
            to: "offscreen",
            type: "blurDetect",
            url,
            blurTarget: blur.target,
          };
          sendResponse((await chrome.runtime.sendMessage(req)) as BlurVerdictResponse);
        } catch (e) {
          // Fail toward blurring. Anything that goes wrong between here and the
          // model is a reason not to know what the image contains, never a
          // reason to show it.
          sendResponse({
            verdict: "blur",
            error: e instanceof Error ? e.message : String(e),
          } satisfies BlurVerdictResponse);
        }
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
