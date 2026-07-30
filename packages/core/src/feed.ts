import { normalizeHost } from "./matcher.js";

/**
 * Line formats the upstream feeds actually use, verified against the live files
 * rather than assumed:
 *
 * - `hosts`        `0.0.0.0 example.com`            StevenBlack, blocklistproject
 * - `domains`      `example.com`                    plain one-per-line
 * - `domainswild`  `*.example.com`                  oisd
 * - `adblock`      `||example.com^`                 hagezi
 */
export type FeedFormat = "hosts" | "domains" | "domainswild" | "adblock";

/**
 * Hostnames a hosts file legitimately maps that are not blocking targets.
 * Without this, parsing `/etc/hosts`-style feeds picks up "localhost" and the
 * app cheerfully sinkholes the loopback name.
 */
const PLACEHOLDER_HOSTS = new Set([
  "localhost",
  "localhost.localdomain",
  "local",
  "broadcasthost",
  "ip6-localhost",
  "ip6-loopback",
  "ip6-localnet",
  "ip6-mcastprefix",
  "ip6-allnodes",
  "ip6-allrouters",
  "ip6-allhosts",
  "0.0.0.0",
]);

/**
 * Top-level domains it is safe — and intended — to block whole.
 *
 * A bare domain with no dot is normally rejected, because storing `com` would
 * make `hostMatchesDomain` match every host under it and take out most of the
 * web. But running the parser against the live feeds showed both hagezi and oisd
 * deliberately publishing exactly that for the adult TLDs: `||www.xxx^`,
 * `*.porn`, `*.sex`, `*.adult`. Those five lines were the only ones either feed
 * lost, and for an adult-content blocker each is worth more than any single
 * domain in the file.
 *
 * So the guard keeps its teeth and gains a whitelist instead. Every entry here
 * is an ICANN-sponsored TLD whose entire purpose is the content we block, so
 * "block all of it" is the correct reading rather than a stray line. Adding to
 * this list means blocking an entire TLD — do it only for one that has no
 * legitimate use for our users.
 *
 * **Two consumers must agree on this set, and one of them is on another
 * platform.** Plaintext matching (`hostMatchesDomain`) handles a stored `xxx`
 * for free, because it compares suffixes. Android stores subscriptions *hashed*,
 * so matching goes through `DomainHasher.candidates`, which deliberately stops
 * before bare single-label names — a collision on `com` against a probabilistic
 * filter would block every .com. That guard used to rest on "a blocklist can
 * never contain a bare name", which this list makes false. `BlockableTlds.kt`
 * holds the Kotlin copy and `candidates` consults it, so an entry that is stored
 * is also looked up. Stored-but-never-looked-up is the failure mode to fear: the
 * app reports the subscription active and blocks nothing from it.
 */
export const BLOCKABLE_TLDS: ReadonlySet<string> = new Set([
  "xxx",
  "porn",
  "sex",
  "adult",
  "sexy",
  "cam",
  "tube",
  "casino",
  "bet",
  "poker",
  "bingo",
]);

/** `||example.com^`, optionally with a trailing `^`, and nothing else. */
const ADBLOCK_PLAIN = /^\|\|([a-z0-9.-]+)\^?$/i;

/** A bare IPv4 literal. A DNS sinkhole answers names, so an address is noise. */
const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;

/**
 * One line to one domain, or null when the line carries nothing we can block.
 *
 * Returning null rather than throwing is deliberate: these files are tens of
 * thousands of lines maintained by other people, and one unparseable line must
 * cost that line rather than the whole subscription.
 *
 * Lifted from `scripts/fetch-upstream-lists.ts` so the build pipeline and the
 * on-device parsers share one definition. `FeedParser.kt` is a hand-port and
 * pins the same vectors — if the two disagree, a subscription parses to nothing
 * and the app looks healthy while blocking nothing extra.
 */
export function parseFeedLine(line: string, format: FeedFormat): string | null {
  const trimmed = line.trim();
  if (trimmed === "") return null;

  // `#` and `!` are the two comment markers in use; `[` catches adblock's
  // `[Adblock Plus]` preamble, which is neither a comment nor a rule.
  if (trimmed.startsWith("#") || trimmed.startsWith("!") || trimmed.startsWith("[")) return null;

  // An adblock exception (`@@||example.com^`) means "do not block". We have no
  // way to express that as an addition to a blocklist, so the line is skipped.
  // Ignoring it can only leave something unblocked that upstream also left
  // unblocked — never the reverse.
  if (trimmed.startsWith("@@")) return null;

  let candidate: string | null = null;

  switch (format) {
    case "hosts": {
      // "0.0.0.0 example.com" / "127.0.0.1 example.com # comment"
      const parts = trimmed.split(/\s+/);
      if (parts.length < 2) return null;
      candidate = parts[1] ?? null;
      break;
    }
    case "domains":
      candidate = trimmed.split(/\s+/)[0] ?? null;
      break;
    case "domainswild": {
      const first = trimmed.split(/\s+/)[0] ?? "";
      // `*.example.com` means the domain and its subdomains — which is exactly
      // what `hostMatchesDomain` already does, so the prefix is dropped rather
      // than being carried into the stored domain.
      candidate = first.startsWith("*.") ? first.slice(2) : first;
      break;
    }
    case "adblock": {
      // Anything with a path, a wildcard, or a `$modifier` is a URL-level rule
      // that a DNS layer cannot express. Skipped rather than mangled into a
      // domain block that would be broader than the author intended.
      candidate = ADBLOCK_PLAIN.exec(trimmed)?.[1] ?? null;
      break;
    }
  }

  if (candidate === null) return null;

  const host = normalizeHost(candidate);
  if (host === "") return null;
  if (PLACEHOLDER_HOSTS.has(host)) return null;
  if (IPV4.test(host)) return null;
  // A domain with no dot is either a placeholder we missed or a bare TLD.
  // A bare TLD is the dangerous case: stored as a domain it would block every
  // host under it, so `com` on one line would take out the internet. The adult
  // and gambling TLDs are the deliberate exception — see `BLOCKABLE_TLDS`.
  if (!host.includes(".") && !BLOCKABLE_TLDS.has(host)) return null;
  // Leftover wildcards mean the line was a pattern we failed to reduce.
  if (host.includes("*")) return null;

  return host;
}

export interface ParsedFeed {
  domains: string[];
  /** Lines that carried no usable domain, for the diagnostics the UI shows. */
  skipped: number;
}

/**
 * Parse a whole feed, de-duplicated and in first-seen order.
 *
 * Reports `skipped` so a subscription that silently produced nothing is
 * distinguishable from one that produced nothing because the download failed —
 * "0 domains, 400,000 skipped" says the format is wrong, "0 and 0" says the
 * body was empty.
 */
export function parseFeed(text: string, format: FeedFormat): ParsedFeed {
  const seen = new Set<string>();
  const domains: string[] = [];
  let skipped = 0;

  for (const line of text.split("\n")) {
    const domain = parseFeedLine(line, format);
    if (domain === null) {
      // Blank lines and comments aren't failures worth reporting.
      const t = line.trim();
      if (t !== "" && !t.startsWith("#") && !t.startsWith("!") && !t.startsWith("[")) skipped++;
      continue;
    }
    if (seen.has(domain)) continue;
    seen.add(domain);
    domains.push(domain);
  }

  return { domains, skipped };
}
