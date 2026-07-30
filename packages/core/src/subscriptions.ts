import type { CategoryId } from "./categories.js";
import type { FeedFormat } from "./feed.js";

/**
 * Blocklists the user can subscribe to, which we are not allowed to ship.
 *
 * **Why this exists.** `scripts/sources.ts` marks fourteen sources
 * `redistributable: false`, because shipping domains inside an app is
 * redistribution — and as that file says, moving our own lists to a private repo
 * did not change it: scrambling and hashing are not licence workarounds. So the
 * largest gambling and adult feeds in the ecosystem sit in this codebase unused.
 *
 * **Why a subscription is different.** If the *user's device* fetches the file
 * from the publisher, we never distribute it, so there is nothing to relicense.
 * GPL-3.0 copyleft attaches to distribution; what we distribute here is a URL
 * and a licence notice. This is the same arrangement AdGuard, uBlock Origin,
 * Pi-hole and AdAway use for these exact feeds: ship a subscription, not a
 * corpus.
 *
 * **Two rules keep it that way, and both are load-bearing:**
 *
 * 1. The device fetches `url` directly. The moment the data passes through
 *    anything of ours — including the GitHub Pages update host — we are
 *    redistributing it.
 * 2. Subscribed domains never enter `baseline/baseline.json` or a
 *    `build:remote` delta. They live only in per-device storage.
 *
 * **This file contains no domains, and must never contain any.** URLs, licences
 * and identifiers only.
 *
 * Only the subset that can actually work is listed. The other ten excluded
 * sources are not omitted out of caution — they cannot function: `openphish` and
 * `phishtank` need registration, `spamhaus` needs a commercial licence,
 * `cisco-talos` publishes IP addresses a DNS sinkhole cannot act on, `easylist`
 * uses URL patterns a DNS layer cannot express, `firebog` is an index of other
 * people's lists rather than a list, and Google Safe Browsing, VirusTotal and
 * Cloudflare Radar are query-time APIs that would send browsing off the device —
 * which contradicts the entire premise of the product, licence or no licence.
 */
export interface Subscription {
  /** Matches the `id` in `scripts/sources.ts`, so the two can be reconciled. */
  id: string;
  category: CategoryId;
  url: string;
  format: FeedFormat;
  /** Publisher, for attribution and for the UI to name who is being trusted. */
  publisher: string;
  license: string;
  homepage: string;
  /**
   * What the user takes on by enabling it, in one sentence, shown *before* the
   * download rather than after. Translated per-platform; this is the English
   * source.
   */
  terms: string;
  /** Approximate download size, for the UI. Measured 2026-07-30. */
  approxBytes: number;
  /** Approximate domain count after parsing. Measured, not from the header. */
  approxDomains: number;
}

export const SUBSCRIPTIONS: readonly Subscription[] = [
  {
    id: "hagezi-gambling",
    category: "list2",
    url: "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/gambling.txt",
    format: "adblock",
    publisher: "HaGeZi",
    license: "GPL-3.0",
    homepage: "https://github.com/hagezi/dns-blocklists",
    terms:
      "Maintained by HaGeZi and licensed GPL-3.0. Downloaded from GitHub to your device; Safe World never hosts a copy.",
    approxBytes: 7_200_000,
    approxDomains: 367_000,
  },
  {
    id: "hagezi-nsfw",
    category: "list3",
    url: "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/nsfw.txt",
    format: "adblock",
    publisher: "HaGeZi",
    license: "GPL-3.0",
    homepage: "https://github.com/hagezi/dns-blocklists",
    terms:
      "Maintained by HaGeZi and licensed GPL-3.0. Downloaded from GitHub to your device; Safe World never hosts a copy.",
    approxBytes: 2_100_000,
    approxDomains: 108_000,
  },
  {
    id: "oisd-nsfw",
    category: "list3",
    // `sources.ts` records this as format "domains", which is wrong: the file is
    // `*.example.com` per line. The old parser would have stored the literal
    // "*.0-0.asia" as a domain, matching nothing. Fixed here and there.
    url: "https://nsfw.oisd.nl/domainswild",
    format: "domainswild",
    publisher: "oisd (Stephan van Ruth)",
    license: "Free for personal use; redistribution not permitted",
    homepage: "https://oisd.nl/",
    terms:
      "Free for personal use. oisd does not permit republishing the list, which is why it is downloaded to your device rather than bundled.",
    approxBytes: 9_000_000,
    approxDomains: 452_000,
  },
];

export function subscriptionsFor(category: CategoryId): readonly Subscription[] {
  return SUBSCRIPTIONS.filter((s) => s.category === category);
}

export function subscriptionById(id: string): Subscription | undefined {
  return SUBSCRIPTIONS.find((s) => s.id === id);
}
