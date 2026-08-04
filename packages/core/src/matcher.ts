import { CATEGORY_IDS, type CategoryId } from "./categories.js";
import type { Settings } from "./types.js";

/**
 * Normalize a hostname or URL into a bare, lowercased registrable-ish host:
 * strips scheme, path, port, userinfo, a leading "www.", and trailing dot.
 * Returns "" for input that has no host.
 */
export function normalizeHost(input: string): string {
  let s = input.trim().toLowerCase();
  if (s === "") return "";

  // Strip scheme.
  s = s.replace(/^[a-z][a-z0-9+.-]*:\/\//, "");
  // Strip userinfo.
  const at = s.indexOf("@");
  if (at !== -1) s = s.slice(at + 1);
  // Strip path/query/fragment.
  s = s.split(/[/?#]/, 1)[0]!;
  // Strip port.
  const colon = s.indexOf(":");
  if (colon !== -1) s = s.slice(0, colon);
  // Strip trailing dot and leading www.
  s = s.replace(/\.$/, "").replace(/^www\./, "");
  return s;
}

/**
 * True if `host` equals `domain` or is a subdomain of it.
 * Both are expected to be normalized (see normalizeHost).
 */
export function hostMatchesDomain(host: string, domain: string): boolean {
  if (host === domain) return true;
  return host.endsWith("." + domain);
}

/** True if `host` matches any domain in the list (exact or subdomain). */
export function hostInList(host: string, domains: Iterable<string>): boolean {
  for (const d of domains) {
    if (d && hostMatchesDomain(host, d)) return true;
  }
  return false;
}

export interface BlockDecision {
  blocked: boolean;
  /** Which category caused the block, "custom" for the user block list, or null. */
  reason: CategoryId | "custom" | null;
}

/**
 * Decide whether a host should be blocked given the user's settings and the
 * per-category blocklists. Precedence: master off → allow; custom allow → allow;
 * custom block → block; otherwise first enabled category that lists it.
 */
export function decide(
  host: string,
  settings: Settings,
  blocklists: Record<CategoryId, Set<string>>,
): BlockDecision {
  const h = normalizeHost(host);
  if (h === "" || !settings.enabled) return { blocked: false, reason: null };

  if (hostInList(h, settings.customAllow.map(normalizeHost))) {
    return { blocked: false, reason: null };
  }
  if (hostInList(h, settings.customBlock.map(normalizeHost))) {
    return { blocked: true, reason: "custom" };
  }

  for (const id of CATEGORY_IDS) {
    if (!settings.categories[id]) continue;
    // A category with no list loaded is treated as empty rather than thrown on.
    // The map is populated asynchronously on some platforms and was missing an
    // entry entirely the first time a seventh category was added — and a
    // matcher that throws blocks *nothing at all*, which is far worse than one
    // category being briefly inert.
    if (hostInList(h, blocklists[id] ?? [])) return { blocked: true, reason: id };
  }
  return { blocked: false, reason: null };
}
