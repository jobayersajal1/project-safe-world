import { isAnalysisCandidate, withBlurDefaults, type BlurSettings } from "@safe-world/core";
import {
  BLUR_SETTINGS_KEY,
  type BlurCheckMessage,
  type BlurVerdictResponse,
} from "../blur/protocol.js";

/**
 * The page half of the blur feature.
 *
 * **Blur first, reveal after.** The stylesheet that hides every image arrives
 * with the document; this script's only job is to take blur *away* from images
 * it has been told are clear. The reverse order — measure, then blur — shows
 * every image for a few frames, and a feature whose failure mode is "you saw it
 * anyway" has no reason to exist.
 *
 * That is also why nothing here blocks. It is registered statically, so it runs
 * on every page whether or not the feature is on, and reads its settings
 * asynchronously afterwards. With the feature off there is no stylesheet and
 * none of this has any effect.
 */

const ATTR = "data-safeworld-blur";
const RADIUS_VAR = "--safeworld-blur-radius";

/**
 * Tags worth checking for a CSS background image. No selector can match "has a
 * background" without resolving styles, so the set has to be narrowed by
 * something cheaper first, and pages carry these by the hundred.
 */
const BACKGROUND_TAGS = "div,span,a,li,section,figure,article,header,footer,td,button,i,em,b,label";

/**
 * Cap on background candidates examined per pass. `getComputedStyle` forces a
 * style resolution; an unbounded sweep of a large page is a visible stall, and
 * anything past the cap is picked up by the next pass.
 */
const MAX_BACKGROUND_CANDIDATES = 400;

let settings: BlurSettings = withBlurDefaults(null);
let observer: IntersectionObserver | null = null;
let mutations: MutationObserver | null = null;

/** Elements whose verdict is outstanding, so re-entering the viewport does not re-ask. */
const pending = new WeakSet<Element>();
/** Images already waiting on `load`, so the listener is attached once. */
const awaitingLoad = new WeakSet<Element>();

const root = document.documentElement;

/* ------------------------------------------------------------------ verdicts */

function markClear(el: Element): void {
  el.setAttribute(ATTR, "off");
  observer?.unobserve(el);
}

function markBlurred(el: Element): void {
  el.setAttribute(ATTR, "on");
  observer?.unobserve(el);
}

/**
 * The URL the browser actually loaded.
 *
 * `currentSrc` is the one that matters: under `srcset` the `src` attribute can
 * name an image the page never fetched, and analysing that would answer a
 * question about a different picture than the one on screen.
 *
 * `blob:` URLs belong to the page's own origin and the offscreen document
 * cannot fetch them, so they are refused here rather than failing later — which
 * leaves them undecided, and therefore blurred.
 */
function imageUrl(el: HTMLImageElement): string {
  const url = el.currentSrc || el.src;
  if (!url || url.startsWith("blob:")) return "";
  return url;
}

/** The `url(...)` of a computed background, or "" for none, gradients and blobs. */
function backgroundUrl(el: Element): string {
  const value = getComputedStyle(el).backgroundImage;
  if (!value || value === "none") return "";
  const match = /url\((['"]?)(.*?)\1\)/.exec(value);
  const url = match?.[2] ?? "";
  if (!url || url.startsWith("blob:")) return "";
  try {
    return new URL(url, location.href).href;
  } catch {
    return "";
  }
}

/** Ask the worker for a verdict, and apply whatever comes back. */
function requestVerdict(el: Element, url: string): void {
  if (pending.has(el)) return;
  pending.add(el);

  const msg: BlurCheckMessage = { type: "blurCheck", url };
  try {
    chrome.runtime.sendMessage(msg, (res: BlurVerdictResponse | undefined) => {
      pending.delete(el);
      // No reply means the worker was torn down mid-flight. Leaving the element
      // undecided keeps it blurred, and the next intersection asks again.
      if (chrome.runtime.lastError || !res) return;
      if (res.verdict === "clear") markClear(el);
      else markBlurred(el);
    });
  } catch {
    // The extension was reloaded and this context is orphaned. Every image on
    // the page stays blurred until it is reloaded, which is the safe direction.
    pending.delete(el);
  }
}

/* ---------------------------------------------------------------- the passes */

/**
 * Decide an element that has just become visible.
 *
 * Size is measured here rather than at discovery because at `document_start`
 * nothing has been laid out — the intersection entry is the first moment a box
 * exists, and it arrives already measured.
 */
function considerOnScreen(el: Element, box: DOMRectReadOnly): void {
  if (el.hasAttribute(ATTR)) return;

  if (el instanceof HTMLImageElement) {
    // A zero rect usually means the image has not loaded yet; fall back to the
    // intrinsic size so a lazily sized image is not written off as an icon.
    const w = box.width || el.naturalWidth;
    const h = box.height || el.naturalHeight;
    if (w === 0 || h === 0) return waitForLoad(el);
    if (!isAnalysisCandidate(w, h, settings.minImagePx)) return markClear(el);

    const url = imageUrl(el);
    if (!url) return waitForLoad(el);
    return requestVerdict(el, url);
  }

  if (!isAnalysisCandidate(box.width, box.height, settings.minImagePx)) return markClear(el);
  const url = backgroundUrl(el);
  if (!url) return markClear(el);
  requestVerdict(el, url);
}

/**
 * Retry once the image has a size.
 *
 * The intersection observer only fires again when the intersection *changes*,
 * so an image that is already fully in view when it finishes loading would
 * otherwise sit undecided — permanently blurred — on a page nobody scrolls.
 */
function waitForLoad(el: HTMLImageElement): void {
  if (awaitingLoad.has(el)) return;
  awaitingLoad.add(el);
  el.addEventListener(
    "load",
    () => {
      awaitingLoad.delete(el);
      if (!el.hasAttribute(ATTR)) considerOnScreen(el, el.getBoundingClientRect());
    },
    { once: true },
  );
}

/**
 * Videos are decided without being looked at.
 *
 * Sampling frames would mean remaking the decision many times a second, and
 * every miss is a face on screen. Blurring for as long as the video plays is
 * the only version of the promise that holds, so this is a setting rather than
 * a detection result.
 */
function decideVideo(el: HTMLVideoElement): void {
  if (settings.blurVideo) markBlurred(el);
  else markClear(el);
}

function adopt(el: Element): void {
  if (el instanceof HTMLVideoElement) return decideVideo(el);
  if (el instanceof HTMLImageElement) {
    if (!el.hasAttribute(ATTR)) observer?.observe(el);
  }
}

/**
 * Take on an element for its CSS background, if it is the shape where that can
 * work.
 *
 * Blurring an element blurs everything inside it. Restricting this to childless,
 * textless elements is not a shortcut — it is the only shape where a `filter`
 * hides the picture without also hiding the words.
 */
function adoptBackground(el: Element): void {
  if (el.hasAttribute(ATTR)) return;
  if (el.childElementCount > 0) return;
  if (el.textContent?.trim()) return;
  if (!el.matches(BACKGROUND_TAGS)) return;
  observer?.observe(el);
}

function scan(scope: ParentNode): void {
  for (const el of scope.querySelectorAll("img,video")) adopt(el);

  let seen = 0;
  for (const el of scope.querySelectorAll(BACKGROUND_TAGS)) {
    if (seen >= MAX_BACKGROUND_CANDIDATES) break;
    seen++;
    adoptBackground(el);
  }
}

/* ---------------------------------------------------------------- lifecycle */

function start(): void {
  if (observer) return;

  observer = new IntersectionObserver(
    (entries) => {
      for (const e of entries) {
        if (e.isIntersecting) considerOnScreen(e.target, e.boundingClientRect);
      }
    },
    // Decide just before the image scrolls into view, so the reveal is not
    // something the user watches happen.
    { rootMargin: "300px" },
  );

  mutations = new MutationObserver((records) => {
    for (const r of records) {
      if (r.type === "attributes") {
        const el = r.target as Element;
        // A new src is a new picture, and the old verdict says nothing about
        // it. A style change is not — it fires constantly on animated pages, so
        // it only ever *adds* an element, never re-opens a settled one.
        if (r.attributeName === "src" || r.attributeName === "srcset") {
          el.removeAttribute(ATTR);
          adopt(el);
        } else {
          adoptBackground(el);
        }
        continue;
      }
      for (const node of r.addedNodes) {
        if (!(node instanceof Element)) continue;
        adopt(node);
        adoptBackground(node);
        scan(node);
      }
    }
  });
  mutations.observe(root, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["src", "srcset", "style"],
  });

  scan(document);
  // The first scan runs at document_start, when the body is usually still
  // empty. The mutation observer covers everything parsed after it was
  // installed; this second pass covers anything that slipped between the two.
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", () => scan(document), { once: true });
  }
}

function stop(): void {
  observer?.disconnect();
  mutations?.disconnect();
  observer = null;
  mutations = null;
}

/** Reflect the current settings onto the document. */
function apply(): void {
  root.style.setProperty(RADIUS_VAR, `${settings.strength}px`);

  if (!settings.enabled) {
    // Unregistering the stylesheet does not pull it out of pages it was already
    // injected into, so it is overridden instead. Existing verdicts are left
    // in place, so switching back on does not re-analyse the whole page.
    root.setAttribute("data-safeworld-blur-disabled", "");
    root.removeAttribute("data-safeworld-reveal");
    stop();
    return;
  }

  root.removeAttribute("data-safeworld-blur-disabled");
  if (settings.revealMode === "never") root.removeAttribute("data-safeworld-reveal");
  else root.setAttribute("data-safeworld-reveal", settings.revealMode);

  start();
  // A video's verdict *is* a setting, so a change to that setting has to reach
  // the ones already decided.
  for (const v of document.querySelectorAll("video")) decideVideo(v);
}

/**
 * Tap to reveal.
 *
 * In the capture phase, and cancelling the event, because a blurred image is
 * very often inside a link: without this the first tap navigates instead of
 * revealing, which reads as the feature ignoring you.
 */
document.addEventListener(
  "click",
  (e) => {
    if (!settings.enabled || settings.revealMode !== "tap") return;
    const target = e.target;
    if (!(target instanceof Element)) return;
    const hit = target.closest(`[${ATTR}="on"]`);
    if (!hit) return;
    hit.setAttribute(ATTR, "off");
    e.preventDefault();
    e.stopPropagation();
  },
  true,
);

chrome.storage.onChanged.addListener((changes, area) => {
  if (area !== "local" || !changes[BLUR_SETTINGS_KEY]) return;
  settings = withBlurDefaults(changes[BLUR_SETTINGS_KEY].newValue as Partial<BlurSettings>);
  apply();
});

void chrome.storage.local.get(BLUR_SETTINGS_KEY).then((raw) => {
  settings = withBlurDefaults(raw[BLUR_SETTINGS_KEY] as Partial<BlurSettings> | undefined);
  apply();
});
