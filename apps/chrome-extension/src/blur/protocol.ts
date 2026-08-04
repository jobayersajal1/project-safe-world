import type { BlurTarget, BlurVerdict } from "@safe-world/core";

/** Where the blur settings live. Its own key — see `packages/core/src/blur.ts`. */
export const BLUR_SETTINGS_KEY = "blurSettings";

/** Built by crxjs at its source-relative path, same as the blocked page. */
export const OFFSCREEN_PATH = "src/offscreen/offscreen.html";

/** Ids of the dynamically registered pieces, so they can be unregistered again. */
export const BLUR_CSS_SCRIPT_ID = "safe-world-blur-css";

/** Path of the boot stylesheet inside the built extension (copied from `public/`). */
export const BLUR_CSS_PATH = "blur.css";

/**
 * Content script → service worker. One message per image rather than a batch:
 * each one unblurs the moment its own verdict lands, and a batch would hold the
 * first image hostage to the slowest.
 */
export interface BlurCheckMessage {
  type: "blurCheck";
  url: string;
}

/**
 * Service worker → offscreen document.
 *
 * `to` exists because `chrome.runtime.sendMessage` from the worker is delivered
 * to *every* extension context — popup and options included. Anything not
 * addressed here is ignored rather than answered, so a stray listener cannot
 * swallow the reply.
 */
export interface BlurDetectMessage {
  to: "offscreen";
  type: "blurDetect";
  url: string;
  blurTarget: BlurTarget;
}

/** Settings changed: every cached verdict was reached under the old target. */
export interface BlurClearCacheMessage {
  to: "offscreen";
  type: "blurClearCache";
}

export type OffscreenMessage = BlurDetectMessage | BlurClearCacheMessage;

export interface BlurVerdictResponse {
  verdict: BlurVerdict;
  /** Set when the verdict is a fallback rather than a decision. */
  error?: string;
}
