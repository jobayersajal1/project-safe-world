/**
 * Blurring people on screen — the platform-agnostic half.
 *
 * Safe World's other feature is all-or-nothing: a site is reachable or it is
 * not. This is the setting in between — the page loads, and photos of people of
 * the chosen gender are blurred in place, so a news site or a shopping page
 * stays usable without having to be blocked outright.
 *
 * Everything here is pure: the settings shape, the rule that turns a set of
 * detected faces into a verdict, and the cache. The models, the pixels and the
 * DOM live in each platform's own code.
 *
 * **`BlurSettings` is stored under its own key, never merged into `Settings`.**
 * Two reasons, and the second is the serious one:
 *
 *  1. The feature only exists on Chrome, Safari and Android. macOS and Windows
 *     would carry dead fields.
 *  2. Adding a non-optional field to the Swift `Settings` silently wipes every
 *     stored setting. `Codable` synthesis makes a new non-optional property
 *     *required*, and `loadSettings` decodes with `try?` — so an existing blob
 *     fails to decode, falls back to defaults, and the user's categories, allow
 *     list and block list all reset while looking like nothing happened.
 */

/** Who gets blurred. */
export type BlurTarget = "women" | "men" | "everyone";

/**
 * How a blurred image can be looked at anyway.
 *
 * Revealing is *weakening* protection, so switching this away from "never"
 * costs the PIN on the platforms that have one — the same rule
 * `ProtectionChange.weakens` already applies to every other setting.
 */
export type BlurRevealMode = "never" | "hover" | "tap";

export interface BlurSettings {
  /** Master switch for this feature alone. */
  enabled: boolean;
  target: BlurTarget;
  /** Blur radius in CSS pixels. */
  strength: number;
  /**
   * Blur videos wholesale, without analysing them.
   *
   * A video is not sampled frame by frame: a decision would have to be remade
   * many times a second, and each miss shows a face. Blurring the element for
   * as long as it plays is the honest version of the promise, so this is a
   * plain on/off rather than a detection setting.
   */
  blurVideo: boolean;
  revealMode: BlurRevealMode;
  /**
   * Images smaller than this on both edges are left alone.
   *
   * Icons, avatars, spacers and tracking pixels are the bulk of the images on a
   * page and none of them is a photograph of a person; analysing them would
   * cost more than the rest of the page put together.
   */
  minImagePx: number;
}

export function defaultBlurSettings(): BlurSettings {
  return {
    enabled: false,
    target: "women",
    strength: 16,
    blurVideo: true,
    revealMode: "never",
    minImagePx: 64,
  };
}

/** Merge stored (possibly partial or older) blur settings over the defaults. */
export function withBlurDefaults(stored: Partial<BlurSettings> | undefined | null): BlurSettings {
  const base = defaultBlurSettings();
  if (!stored) return base;
  return { ...base, ...stored };
}

/** One face the detector found, reduced to what the verdict actually needs. */
export interface FaceObservation {
  /** What the classifier called it. */
  gender: "male" | "female";
  /** How sure it was, 0..1. */
  genderProbability: number;
}

/**
 * Below this, the classifier is close enough to a coin flip that its answer
 * carries no information — and an uncertain face is blurred, so the threshold
 * is what turns "don't know" into "blur".
 *
 * Deliberately high. Gender classification is weakest on exactly the faces this
 * audience sees most: children, side profiles, small thumbnails, and hijab or
 * niqab. Being wrong in the direction of blurring costs an annoyance; being
 * wrong the other way costs the user the thing they installed this to prevent.
 */
export const GENDER_CONFIDENCE = 0.75;

export type BlurVerdict = "blur" | "clear";

/**
 * Whether an image containing these faces should stay blurred.
 *
 * The unit is the whole image, not the face. Bodies, hair and clothing are the
 * bulk of what needs covering and no face box contains them, and an image with
 * a person of each gender cannot be half-blurred usefully.
 *
 * **Uncertainty blurs.** A face the classifier is unsure about is treated as if
 * it were the target — a missed blur is the failure the user installed this to
 * prevent, an over-blur is an inconvenience.
 */
export function verdictForFaces(
  faces: readonly FaceObservation[],
  target: BlurTarget,
  confidence: number = GENDER_CONFIDENCE,
): BlurVerdict {
  if (faces.length === 0) return "clear";
  if (target === "everyone") return "blur";

  const wanted = target === "women" ? "female" : "male";
  for (const f of faces) {
    if (f.genderProbability < confidence) return "blur";
    if (f.gender === wanted) return "blur";
  }
  return "clear";
}

/**
 * True if an image of this size is worth decoding and running two models over.
 *
 * Both edges have to clear the bar: a 1000×8 banner strip and a 16×16 favicon
 * are equally not photographs of people, and a page carries dozens of each.
 */
export function isAnalysisCandidate(
  width: number,
  height: number,
  minPx: number,
): boolean {
  return width >= minPx && height >= minPx;
}

/**
 * Verdicts keyed by image URL, bounded and least-recently-used.
 *
 * The same image URL recurs constantly — a logo on every page, a thumbnail in
 * a feed that re-renders on scroll — and each miss costs a fetch, a decode and
 * two model passes. Unbounded, this would hold every image URL seen since the
 * browser started; the offscreen document that owns it is long-lived by design,
 * which is exactly what makes the bound necessary.
 */
export class VerdictCache {
  private readonly map = new Map<string, BlurVerdict>();

  constructor(private readonly limit = 4000) {}

  get(url: string): BlurVerdict | undefined {
    const hit = this.map.get(url);
    if (hit === undefined) return undefined;
    // Re-insert to move it to the end: Map iterates in insertion order, so the
    // oldest key is simply the first one.
    this.map.delete(url);
    this.map.set(url, hit);
    return hit;
  }

  set(url: string, verdict: BlurVerdict): void {
    if (this.map.has(url)) this.map.delete(url);
    this.map.set(url, verdict);
    while (this.map.size > this.limit) {
      const oldest = this.map.keys().next();
      if (oldest.done) break;
      this.map.delete(oldest.value);
    }
  }

  get size(): number {
    return this.map.size;
  }

  clear(): void {
    this.map.clear();
  }
}
