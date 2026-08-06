import type { CategoryId } from "./categories.js";
import { normalizeHost } from "./matcher.js";

/**
 * The advisory model — a guess about a domain nobody has listed yet.
 *
 * The blocklists are exact-membership sets, and excellent at what they do, but
 * a gambling site registered this morning is not in them and stays reachable
 * until someone upstream adds it, we re-fetch, and a delta reaches the device.
 * This is the second stage that closes some of that gap: hashed character
 * n-grams of the hostname into a small linear model, one per category.
 *
 * **It never blocks, and it never touches `decide()`.** `decide()` remains the
 * tested spec of blocking behaviour and runs first; this only ever looks at a
 * host `decide()` already allowed, and the strongest thing it can say is
 * "warn". A wrong guess costs a click on "continue anyway", not a broken site,
 * and that asymmetry is the whole reason the model can be tuned to catch far
 * more than a block-only design would dare.
 *
 * Two properties are load-bearing rather than incidental:
 *
 * - **It cannot memorise the list.** A linear model over hashed n-grams has no
 *   way to reproduce a domain, so shipping these weights does not ship the
 *   blocklist — which matters, because nothing in this repo contains a domain
 *   in any encoding.
 * - **It ports in about thirty lines.** No ML runtime, no platform hash, no
 *   float format to reconcile: FNV-1a, a dot product, a comparison.
 *   `DomainModel.kt`, `DomainModel.swift` and `DomainModel.cs` reimplement this
 *   by hand and pin the same vectors, the way `Scramble` and `DomainHasher`
 *   already do. **Nothing here may change without changing all of them.**
 *
 * Only the categories that earned it are shipped. Measured on 1,790,722
 * held-out negatives with a split grouped by registrable domain, gambling
 * catches ~22% of names it has never seen at under one false positive per
 * million, and adult ~24% at roughly five. Scam, social, games and drugs did
 * not clear that bar and carry no model; see the plan for the numbers.
 */

/** Its own storage key, never merged into `Settings` — see `blur.ts`. */
export const ADVISORY_STORAGE_KEY = "advisory";

/** The categories a model shipped for. Everything else is list-only. */
export const ADVISORY_CATEGORIES: readonly CategoryId[] = ["list2", "list3"];

export interface AdvisorySettings {
  /** Master switch. Ships **off**: this is a guess, and opting in should be a choice. */
  enabled: boolean;
  /** Per-category, because the two shipped models are not equally good. */
  categories: Partial<Record<CategoryId, boolean>>;
}

export function defaultAdvisorySettings(): AdvisorySettings {
  return { enabled: false, categories: { list2: true, list3: true } };
}

export function withAdvisoryDefaults(stored: Partial<AdvisorySettings> | null | undefined): AdvisorySettings {
  const base = defaultAdvisorySettings();
  if (!stored) return base;
  return {
    enabled: typeof stored.enabled === "boolean" ? stored.enabled : base.enabled,
    categories: { ...base.categories, ...(stored.categories ?? {}) },
  };
}

// ---------------------------------------------------------------- the features

export const HASH_BITS = 18;
export const TABLE_SIZE = 1 << HASH_BITS;
const TABLE_MASK = TABLE_SIZE - 1;
const NGRAMS = [3, 4, 5] as const;

const FNV_OFFSET = 0x811c9dc5;
const FNV_PRIME = 0x01000193;

const encoder = new TextEncoder();

function fnv1a(bytes: Uint8Array, start: number, end: number): number {
  let h = FNV_OFFSET;
  for (let i = start; i < end; i++) {
    h = Math.imul(h ^ bytes[i]!, FNV_PRIME) >>> 0;
  }
  return h;
}

function bucket(value: number, edges: readonly number[]): number {
  for (let i = 0; i < edges.length; i++) {
    if (value <= edges[i]!) return i;
  }
  return edges.length;
}

/**
 * Whole-name properties no n-gram window can express.
 *
 * A 5-gram sees `.xyz` only in combination with whatever precedes it, so a
 * cheap TLD costs thousands of separate weights to learn. Same for "forty
 * characters of digits and hyphens across two labels", which is most of what
 * separates a throwaway domain from an ordinary one. These are hashed into the
 * same table as the n-grams, so they cost nothing in format or porting effort —
 * they are just more strings to hash. Measured worth: +7 points of recall on
 * scam, +11 on dating, more than any regularisation setting was worth.
 */
function structuralTokens(host: string): string[] {
  const labels = host.split(".");
  let digits = 0;
  let hyphens = 0;
  for (const c of host) {
    if (c >= "0" && c <= "9") digits++;
    else if (c === "-") hyphens++;
  }
  let longest = 0;
  for (const l of labels) longest = Math.max(longest, l.length);
  return [
    "tld=" + (labels.length > 1 ? labels[labels.length - 1]! : ""),
    "sld=" + (labels.length > 1 ? labels[labels.length - 2]! : ""),
    "labels=" + Math.min(labels.length, 6),
    "len=" + bucket(host.length, [8, 12, 16, 20, 26, 34, 48]),
    "digits=" + bucket(Math.floor((100 * digits) / Math.max(1, host.length)), [0, 5, 15, 30, 50]),
    "hyphens=" + bucket(hyphens, [0, 1, 2, 4]),
    "longest=" + bucket(longest, [4, 7, 10, 14, 20, 30]),
  ];
}

/**
 * The L2-normalised sparse feature vector for a host.
 *
 * Normalisation is not cosmetic: hostnames vary in length by an order of
 * magnitude, and an unnormalised count vector scores a long name highly for
 * being long. That is precisely the false positive this must not make.
 *
 * The sign comes from bit 31 of the same hash. Two n-grams colliding in the
 * table then cancel as often as they reinforce, so a collision costs noise
 * rather than a systematic lean toward "blocked".
 */
export function advisoryFeatures(host: string): Map<number, number> {
  const clean = normalizeHost(host);
  const acc = new Map<number, number>();
  if (clean === "") return acc;

  const bytes = encoder.encode("^" + clean + "$");
  const add = (h: number): void => {
    const idx = h & TABLE_MASK;
    const sign = (h >>> 31) & 1 ? -1 : 1;
    acc.set(idx, (acc.get(idx) ?? 0) + sign);
  };

  for (const n of NGRAMS) {
    for (let i = 0; i + n <= bytes.length; i++) add(fnv1a(bytes, i, i + n));
  }
  for (const token of structuralTokens(clean)) {
    const t = encoder.encode(token);
    add(fnv1a(t, 0, t.length));
  }

  let sum = 0;
  for (const v of acc.values()) sum += v * v;
  if (sum === 0) return new Map();
  const inv = 1 / Math.sqrt(sum);
  for (const [k, v] of acc) acc.set(k, v * inv);
  return acc;
}

// ------------------------------------------------------------------- the model

export interface DomainModel {
  category: CategoryId;
  /** int8 weights times this. One shared scale: a per-block scale would have to be reproduced exactly by four ports for one decimal place of nothing. */
  scale: number;
  bias: number;
  /** Score at or above which the host is worth warning about. Chosen at export time from the measured operating point, not guessed. */
  threshold: number;
  weights: Int8Array;
}

export interface SerializedDomainModel {
  category: CategoryId;
  tableSize: number;
  scale: number;
  bias: number;
  threshold: number;
  /** base64 of `tableSize` int8 weights. */
  weights: string;
}

export function loadDomainModel(data: SerializedDomainModel): DomainModel {
  if (data.tableSize !== TABLE_SIZE) {
    // Refusing beats scoring against a mismatched table, which produces
    // confident nonsense rather than an obvious failure.
    throw new Error(`advisory model ${data.category}: table ${data.tableSize}, expected ${TABLE_SIZE}`);
  }
  const binary = atob(data.weights);
  const weights = new Int8Array(binary.length);
  for (let i = 0; i < binary.length; i++) weights[i] = (binary.charCodeAt(i) << 24) >> 24;
  if (weights.length !== TABLE_SIZE) {
    throw new Error(`advisory model ${data.category}: ${weights.length} weights, expected ${TABLE_SIZE}`);
  }
  return {
    category: data.category,
    scale: data.scale,
    bias: data.bias,
    threshold: data.threshold,
    weights,
  };
}

export function scoreHost(model: DomainModel, host: string): number {
  let sum = 0;
  for (const [idx, value] of advisoryFeatures(host)) sum += model.weights[idx]! * value;
  return sum * model.scale + model.bias;
}

// ------------------------------------------------------------------ the guards

/**
 * Hosts on a shared publishing platform are never scored.
 *
 * This is not a nicety, it is the failure that showed up in measurement. Adult
 * Blogger blogs are heavily represented in the adult list, so the model learned
 * that `*.blogspot.com` is adult — and then flagged Blogger's own image CDN
 * (`1.bp.blogspot.com`, and `2.` and `3.`, which serve the pictures on every
 * Blogger blog there is) along with `videoseriesbiblicas.blogspot.com`, a Bible
 * video series. The name of a subdomain on a shared host says something about
 * that one blog and nothing about the platform, and we cannot tell the two
 * apart from the string, so we decline to guess.
 *
 * These are platform names, not blocklist entries — the same kind of fact as
 * `normalizeHost` knowing about `www.` — so they live in code.
 */
const SHARED_PLATFORMS = [
  "blogspot.com",
  "bp.blogspot.com",
  "wordpress.com",
  "tumblr.com",
  "medium.com",
  "github.io",
  "gitlab.io",
  "gitbook.io",
  "weebly.com",
  "weeblysite.com",
  "wixsite.com",
  "webflow.io",
  "pages.dev",
  "vercel.app",
  "netlify.app",
  "herokuapp.com",
  "duckdns.org",
  "000webhostapp.com",
  "blogger.com",
  "substack.com",
  "notion.site",
  "myshopify.com",
  "translate.goog",
  "googleusercontent.com",
  "cloudfront.net",
  "akamaized.net",
  "amazonaws.com",
];

export function isSharedPlatformHost(host: string): boolean {
  const clean = normalizeHost(host);
  for (const platform of SHARED_PLATFORMS) {
    if (clean === platform) return true;
    if (clean.endsWith("." + platform)) return true;
  }
  return false;
}

export type AdvisoryVerdict =
  | { advise: false }
  | { advise: true; category: CategoryId; score: number };

export interface AdviseOptions {
  /**
   * Return true for a host that must never be advised against — a popular-sites
   * allowlist, typically a fuse filter built alongside the blocklists.
   *
   * Kept injectable rather than bundled here because it is a large list of
   * domains, and this package ships none.
   */
  isWellKnown?: (host: string) => boolean;
}

/**
 * The second stage. Call only for a host `decide()` returned as allowed.
 *
 * The first enabled category to clear its threshold wins, so the order of
 * `models` is the order of precedence. Nothing here is a block.
 */
export function advise(
  host: string,
  models: readonly DomainModel[],
  settings: AdvisorySettings,
  options: AdviseOptions = {},
): AdvisoryVerdict {
  if (!settings.enabled) return { advise: false };
  const clean = normalizeHost(host);
  if (clean === "") return { advise: false };
  if (isSharedPlatformHost(clean)) return { advise: false };
  if (options.isWellKnown?.(clean)) return { advise: false };

  for (const model of models) {
    if (settings.categories[model.category] !== true) continue;
    const score = scoreHost(model, clean);
    if (score >= model.threshold) return { advise: true, category: model.category, score };
  }
  return { advise: false };
}

/**
 * A host is looked at once, not once per request.
 *
 * `decide()` runs on the DNS hot path — once per query in the Android tunnel —
 * and scoring is orders of magnitude dearer than a fuse lookup. Unique hosts
 * per device per day are in the thousands; queries are far more.
 */
export class AdvisoryCache {
  private readonly entries = new Map<string, AdvisoryVerdict>();

  constructor(private readonly limit = 4096) {}

  get(host: string, compute: () => AdvisoryVerdict): AdvisoryVerdict {
    const hit = this.entries.get(host);
    if (hit !== undefined) {
      // Refresh recency so the working set survives; a plain Map iterates in
      // insertion order, which is what makes the eviction below LRU.
      this.entries.delete(host);
      this.entries.set(host, hit);
      return hit;
    }
    const verdict = compute();
    this.entries.set(host, verdict);
    if (this.entries.size > this.limit) {
      const oldest = this.entries.keys().next();
      if (!oldest.done) this.entries.delete(oldest.value);
    }
    return verdict;
  }

  clear(): void {
    this.entries.clear();
  }
}
