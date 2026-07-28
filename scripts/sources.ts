import type { CategoryId } from "../packages/core/src/categories.js";

/**
 * Upstream blocklist sources.
 *
 * **`redistributable` is the field that matters.** We publish a merged list to
 * a public repo, which is redistribution. Several well-known feeds don't permit
 * that — some are query-time APIs whose terms forbid republishing their data,
 * others are free only for personal use. Those are recorded here with
 * `redistributable: false` and skipped by default, so the licensing decision is
 * visible in code rather than being made by accident.
 *
 * If you enable a non-redistributable source you take on its terms; several
 * require a commercial agreement.
 */
export interface Source {
  id: string;
  category: CategoryId;
  url: string;
  /**
   * hosts   `0.0.0.0 example.com`
   * domains `example.com`
   * adblock `||example.com^`
   */
  format: "hosts" | "domains" | "adblock";
  license: string;
  redistributable: boolean;
  homepage: string;
  /** Why it's excluded, when it is. */
  note?: string;
}

export const SOURCES: readonly Source[] = [
  // ---------------------------------------------------------------- included
  {
    id: "stevenblack-gambling-porn",
    category: "list2",
    url: "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling-only/hosts",
    format: "hosts",
    license: "MIT",
    redistributable: true,
    homepage: "https://github.com/StevenBlack/hosts",
  },
  {
    id: "stevenblack-porn",
    category: "list3",
    url: "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
    format: "hosts",
    license: "MIT",
    redistributable: true,
    homepage: "https://github.com/StevenBlack/hosts",
  },
  {
    id: "phishing-database-active",
    category: "list1",
    url: "https://raw.githubusercontent.com/Phishing-Database/Phishing.Database/master/phishing-domains-ACTIVE.txt",
    format: "domains",
    license: "See repo; feeds are aggregated from public sources",
    redistributable: true,
    homepage: "https://github.com/Phishing-Database/Phishing.Database",
  },
  {
    id: "urlhaus-abuse-ch",
    category: "list1",
    url: "https://urlhaus.abuse.ch/downloads/hostfile/",
    format: "hosts",
    license: "CC0",
    redistributable: true,
    homepage: "https://urlhaus.abuse.ch/",
  },
  {
    id: "stevenblack-social",
    category: "list4",
    url: "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/social-only/hosts",
    format: "hosts",
    license: "MIT",
    redistributable: true,
    homepage: "https://github.com/StevenBlack/hosts",
  },
  {
    id: "blocklistproject-facebook",
    category: "list4",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/facebook.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-tiktok",
    category: "list4",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/tiktok.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-twitter",
    category: "list4",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/twitter.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-youtube",
    category: "list5",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/youtube.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },

  // --------------------------------------------------------------- excluded
  // Kept as documentation of what was considered and why it isn't used.
  {
    id: "hagezi-gambling",
    category: "list2",
    url: "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/gambling.txt",
    format: "adblock",
    license: "GPL-3.0",
    redistributable: false,
    homepage: "https://github.com/hagezi/dns-blocklists",
    note: "GPL-3.0 copyleft. Redistributing a merged list arguably makes the whole published list GPL. Decide deliberately before enabling.",
  },
  {
    id: "hagezi-nsfw",
    category: "list3",
    url: "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/nsfw.txt",
    format: "adblock",
    license: "GPL-3.0",
    redistributable: false,
    homepage: "https://github.com/hagezi/dns-blocklists",
    note: "Same GPL-3.0 question as hagezi-gambling.",
  },
  {
    id: "oisd-nsfw",
    category: "list3",
    url: "https://nsfw.oisd.nl/domainswild",
    format: "domains",
    license: "Free for personal use; redistribution restricted",
    redistributable: false,
    homepage: "https://oisd.nl/",
    note: "oisd's terms don't permit republishing the list.",
  },
  {
    id: "openphish",
    category: "list1",
    url: "https://openphish.com/feed.txt",
    format: "domains",
    license: "Free feed is non-commercial; redistribution needs a licence",
    redistributable: false,
    homepage: "https://openphish.com/",
    note: "Republishing the feed requires a commercial agreement.",
  },
  {
    id: "phishtank",
    category: "list1",
    url: "https://data.phishtank.com/data/online-valid.csv",
    format: "domains",
    license: "Requires API key and attribution; redistribution restricted",
    redistributable: false,
    homepage: "https://phishtank.org/",
    note: "Needs a registered API key; terms restrict redistribution.",
  },
  {
    id: "spamhaus",
    category: "list1",
    url: "https://www.spamhaus.org/",
    format: "domains",
    license: "Proprietary; commercial licence required",
    redistributable: false,
    homepage: "https://www.spamhaus.org/",
    note: "Strictly licensed. Not a free bulk download.",
  },
  {
    id: "google-safe-browsing",
    category: "list1",
    url: "https://developers.google.com/safe-browsing",
    format: "domains",
    license: "API only; terms forbid republishing the data",
    redistributable: false,
    homepage: "https://developers.google.com/safe-browsing",
    note: "A query-time API, not a list. Would have to be called per-lookup, which conflicts with on-device matching and sends browsing data off the device.",
  },
  {
    id: "virustotal",
    category: "list1",
    url: "https://www.virustotal.com/",
    format: "domains",
    license: "API; terms forbid redistribution of results",
    redistributable: false,
    homepage: "https://www.virustotal.com/",
    note: "Query-time API with strict quota and redistribution terms.",
  },
  {
    id: "cloudflare-radar",
    category: "list1",
    url: "https://radar.cloudflare.com/",
    format: "domains",
    license: "API; terms restrict redistribution",
    redistributable: false,
    homepage: "https://radar.cloudflare.com/",
    note: "Ranking/intelligence API, not a blocklist feed.",
  },
  {
    id: "easylist",
    category: "list1",
    url: "https://easylist.to/easylist/easylist.txt",
    format: "adblock",
    license: "GPL-3.0 / CC BY-SA 3.0",
    redistributable: false,
    homepage: "https://github.com/easylist/easylist",
    note: "Ad and tracker filters, not scam/gambling/adult — out of scope for these categories, and uses URL-pattern syntax a DNS sinkhole can't express.",
  },
  {
    id: "1hosts",
    category: "list1",
    url: "https://raw.githubusercontent.com/badmojr/1Hosts/master/Pro/domains.txt",
    format: "domains",
    license: "CC BY-SA 4.0",
    redistributable: false,
    homepage: "https://github.com/badmojr/1Hosts",
    note: "Share-alike; mostly ads/tracking rather than our three categories.",
  },
];

export const INCLUDED_SOURCES = SOURCES.filter((s) => s.redistributable);
export const EXCLUDED_SOURCES = SOURCES.filter((s) => !s.redistributable);
