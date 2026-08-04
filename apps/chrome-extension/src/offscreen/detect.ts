import * as faceapi from "@vladmandic/face-api";
import {
  VerdictCache,
  verdictForFaces,
  type BlurTarget,
  type FaceObservation,
} from "@safe-world/core";
import type { OffscreenMessage, BlurVerdictResponse } from "../blur/protocol.js";

/**
 * Longest edge the image is scaled to before detection.
 *
 * TinyFaceDetector resizes to its own 416px input anyway, so anything much
 * larger is decoded and discarded; anything much smaller loses the faces that
 * are hardest to catch and most important to catch — the small ones in a
 * crowded photo.
 */
const MAX_EDGE = 512;

/**
 * The image is detected over twice, inset by these fractions on every side.
 *
 * TinyFaceDetector is anchor-based, so it only sees faces within a band of
 * scales relative to its input. A face that fills the frame — a cropped
 * headshot, which is to say an avatar or a profile picture — falls off the top
 * of that band and is **missed entirely**, which is the one failure this
 * feature cannot afford: no faces found is indistinguishable from a photo of
 * nobody, so a missed face is not blurred.
 *
 * Insetting shrinks the face relative to the frame and brings it back into
 * range. One inset is not enough, because the value that rescues a full-frame
 * crop loses faces in a group shot; the two together find both, and the union
 * can only ever add. Measured on cropped headshots and group photos: 0.3 alone
 * missed a tight crop, 0.5 alone dropped a six-face group to three, and both
 * together found every face either did.
 *
 * Duplicates across the passes are left in. Nothing downstream counts faces —
 * the verdict asks only whether any of them matches or is uncertain — and a
 * duplicate can only push toward blurring.
 */
const DETECTION_INSETS = [0.3, 0.5];

/**
 * Kept below face-api's 0.5 default. A weak detection still gets classified,
 * and a face the classifier is unsure about is blurred — so admitting more
 * candidates costs an occasional over-blur, while excluding them costs a miss.
 */
const SCORE_THRESHOLD = 0.3;

/**
 * Below this on either edge the source image is not a photograph of a person,
 * whatever the page stretched it to. Sprites and spacers arrive here when a
 * page displays a 16px asset at 300px.
 */
const MIN_NATURAL_PX = 32;

/** A single image should never hold the queue up for longer than this. */
const FETCH_TIMEOUT_MS = 10_000;

/** Past this, decoding costs more than the verdict is worth. */
const MAX_BYTES = 12 * 1024 * 1024;

/**
 * Images decoded at once.
 *
 * One at a time leaves the GPU idle between fetches; many at once thrashes it
 * and makes the *first* verdict — the one the user is waiting on — arrive last.
 */
const CONCURRENCY = 3;

const cache = new VerdictCache(4000);

/**
 * Backend control, which face-api ships but does not declare.
 *
 * `tfjs.esm.d.ts` re-exports a hand-written subset of tfjs — tensors and ops —
 * and leaves out `setBackend`/`ready`, though both are exported by the bundle
 * and are the documented way to choose a backend. The cast names exactly what
 * is used rather than widening the whole namespace to `any`.
 */
const tf = faceapi.tf as unknown as {
  setBackend(name: string): Promise<boolean>;
  ready(): Promise<void>;
};

let modelsReady: Promise<void> | null = null;
let inFlight = 0;
const queue: (() => void)[] = [];

/**
 * Load both models once.
 *
 * They are fetched from the packaged extension, never from a CDN: this feature's
 * whole promise is that no image and no request about an image leaves the
 * machine.
 */
function loadModels(): Promise<void> {
  modelsReady ??= (async () => {
    // WebGL is the only backend fast enough to keep up with a scrolling page.
    // The fallback is not decoration — an offscreen document is never composited,
    // and on machines where that costs it a GPU context, a slow verdict is still
    // a verdict while a thrown error would leave every image blurred forever.
    try {
      await tf.setBackend("webgl");
      await tf.ready();
    } catch {
      await tf.setBackend("cpu");
      await tf.ready();
    }

    const uri = chrome.runtime.getURL("models");
    await faceapi.nets.tinyFaceDetector.loadFromUri(uri);
    await faceapi.nets.ageGenderNet.loadFromUri(uri);
  })();
  return modelsReady;
}

/** Run `fn` once a detection slot is free. */
function withSlot<T>(fn: () => Promise<T>): Promise<T> {
  return new Promise<T>((resolve, reject) => {
    const start = () => {
      inFlight++;
      fn().then(resolve, reject).finally(() => {
        inFlight--;
        queue.shift()?.();
      });
    };
    if (inFlight < CONCURRENCY) start();
    else queue.push(start);
  });
}

/**
 * Decode the image to a canvas the detector can read.
 *
 * **This is the function the whole design turns on.** Drawing a third-party
 * `<img>` element to a canvas taints it and every later read throws a security
 * error — which is why the page element is never touched. The extension holds
 * `<all_urls>`, so it fetches the bytes itself and decodes them from a blob,
 * and the resulting canvas is clean.
 *
 * `force-cache` matters as much: the page has already downloaded this image, and
 * without it every analysed image would be paid for twice on the network.
 */
async function decode(url: string): Promise<HTMLCanvasElement | null> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const res = await fetch(url, { signal: controller.signal, cache: "force-cache" });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);

    const length = Number(res.headers.get("content-length") ?? 0);
    if (length > MAX_BYTES) throw new Error("too large");

    const blob = await res.blob();
    if (blob.size > MAX_BYTES) throw new Error("too large");

    const bitmap = await createImageBitmap(blob);
    try {
      if (bitmap.width < MIN_NATURAL_PX || bitmap.height < MIN_NATURAL_PX) return null;

      const scale = Math.min(1, MAX_EDGE / Math.max(bitmap.width, bitmap.height));
      const canvas = document.createElement("canvas");
      canvas.width = Math.max(1, Math.round(bitmap.width * scale));
      canvas.height = Math.max(1, Math.round(bitmap.height * scale));
      const ctx = canvas.getContext("2d", { willReadFrequently: true });
      if (!ctx) throw new Error("no 2d context");
      ctx.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
      return canvas;
    } finally {
      bitmap.close();
    }
  } finally {
    clearTimeout(timer);
  }
}

/** Copy `source` onto a larger canvas, inset by `fraction` on every side. */
function inset(source: HTMLCanvasElement, fraction: number): HTMLCanvasElement {
  const mx = Math.round(source.width * fraction);
  const my = Math.round(source.height * fraction);
  const padded = document.createElement("canvas");
  padded.width = source.width + mx * 2;
  padded.height = source.height + my * 2;
  const ctx = padded.getContext("2d");
  if (!ctx) return source;
  // Mid-grey rather than black or transparent: a hard border is an edge, and an
  // edge is the kind of structure a detector can find a face in.
  ctx.fillStyle = "#808080";
  ctx.fillRect(0, 0, padded.width, padded.height);
  ctx.drawImage(source, mx, my);
  return padded;
}

async function facesIn(canvas: HTMLCanvasElement): Promise<FaceObservation[]> {
  const observations: FaceObservation[] = [];

  for (const fraction of DETECTION_INSETS) {
    const found = await faceapi
      .detectAllFaces(
        inset(canvas, fraction),
        new faceapi.TinyFaceDetectorOptions({ inputSize: 416, scoreThreshold: SCORE_THRESHOLD }),
      )
      .withAgeAndGender();

    for (const f of found) {
      observations.push({
        gender: f.gender === "female" ? "female" : "male",
        genderProbability: f.genderProbability,
      });
    }
  }

  return observations;
}

async function analyse(url: string, blurTarget: BlurTarget): Promise<BlurVerdictResponse> {
  const hit = cache.get(url);
  if (hit) return { verdict: hit };

  try {
    await loadModels();
    const canvas = await withSlot(() => decode(url));
    // Too small to be a photograph of anyone — not a failure, a decision.
    if (!canvas) {
      cache.set(url, "clear");
      return { verdict: "clear" };
    }

    const verdict = verdictForFaces(await facesIn(canvas), blurTarget);
    cache.set(url, verdict);
    return { verdict };
  } catch (e) {
    // Fail toward blurring, and do not cache it. A blob: URL from the page's
    // own origin, an image behind auth, a decode that ran out of memory — none
    // of those are evidence that the image is safe to show, and a later attempt
    // may well succeed.
    return { verdict: "blur", error: e instanceof Error ? e.message : String(e) };
  }
}

chrome.runtime.onMessage.addListener((msg: OffscreenMessage, _sender, sendResponse) => {
  // Anything not addressed here is ignored, not answered: the worker broadcasts
  // to every extension context, and responding to a message meant for the popup
  // would silence the context that was supposed to handle it.
  if (msg?.to !== "offscreen") return false;

  switch (msg.type) {
    case "blurDetect":
      void analyse(msg.url, msg.blurTarget).then(sendResponse);
      return true;
    case "blurClearCache":
      cache.clear();
      sendResponse({ verdict: "clear" } satisfies BlurVerdictResponse);
      return true;
    default:
      return false;
  }
});
