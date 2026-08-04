import type { CategoryId } from "../packages/core/src/categories.js";
import type { FeedFormat } from "../packages/core/src/feed.js";

/**
 * Upstream blocklist sources.
 *
 * **`redistributable` is the field that matters.** We ship a merged list inside
 * every app, which is redistribution. Note that moving the lists to a private
 * repo did **not** change this: shipping domains in an app redistributes them
 * whether they are scrambled, hashed, or plain, and encoding is not a licence
 * workaround. Several well-known feeds don't permit redistribution — some are
 * query-time APIs whose terms forbid republishing their data, others are free
 * only for personal use. Those are recorded here with `redistributable: false`
 * and skipped, so the licensing decision is visible in code rather than being
 * made by accident.
 *
 * If you enable a non-redistributable source you take on its terms; several
 * require a commercial agreement.
 */
export interface Source {
  id: string;
  category: CategoryId;
  url: string;
  /** See `FeedFormat` in packages/core/src/feed.ts for what each one looks like. */
  format: FeedFormat;
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
    id: "blocklistproject-fortnite",
    category: "list6",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/fortnite.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
    note: "Was the only gaming feed the usual DNS-blocklist maintainers publish, and it has since shrunk to a handful of entries. Kept because it costs nothing; UT1 below is what actually populates this category now.",
  },

  // The Université Toulouse 1 Capitole blacklist — the long-running academic one
  // that school and library filters have used for two decades. It is the reason
  // `list6` and the dating half of `list4` are no longer hand-curated: the DNS
  // blocklist world categorises by *harm* (malware, phishing, porn, gambling),
  // so it has nothing for "games" or "dating", while UT1 categorises by
  // *subject* and therefore does.
  //
  // **CC BY-SA, which is a heavier obligation than the rest of this file.**
  // Attribution is satisfied by the `sources` block written into every list
  // file. ShareAlike is the part to be deliberate about: it attaches to adapted
  // versions of the data, so the merged lists we publish inherit it. That is a
  // decision about the *lists*, not about this repo's code — the code is
  // unaffected either way, and the lists were already redistributed under a mix
  // of upstream terms.
  {
    id: "ut1-games",
    category: "list6",
    url: "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/games/domains",
    format: "domains",
    license: "CC BY-SA 4.0",
    redistributable: true,
    homepage: "https://dsi.ut-capitole.fr/blacklists/",
    note: "Game portals, launchers and publisher domains — the time-sink half of what people mean by 'block games'. Real-money games go to list2 instead; see the note there.",
  },
  {
    id: "ut1-dating",
    category: "list4",
    url: "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/dating/domains",
    format: "domains",
    license: "CC BY-SA 4.0",
    redistributable: true,
    homepage: "https://dsi.ut-capitole.fr/blacklists/",
  },
  {
    id: "ut1-gambling",
    category: "list2",
    url: "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/gambling/domains",
    format: "domains",
    license: "CC BY-SA 4.0",
    redistributable: true,
    homepage: "https://dsi.ut-capitole.fr/blacklists/",
    note: "Carries the real-money card and rummy sites (teen patti, rummy, fantasy cricket) that the English-language gambling feeds under-cover.",
  },
  // `list7`. Drugs are the feed-backed half; piracy rides along because both are
  // the same answer to the same question rather than two switches for one
  // intention. Mandatory for the same reason scam, gambling and adult are.
  //
  // **Alcohol and tobacco are in this category but not in this file**, because
  // no maintainer publishes a feed for either — they are not standard blocklist
  // categories the way malware and porn are, and UT1's French naming is not
  // hiding one (`alcool` and `tabac` are both 404). They are curated entries in
  // `list7.json` instead, which `fetch:lists` preserves across refreshes.
  {
    id: "blocklistproject-drugs",
    category: "list7",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/drugs.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "ut1-drugs",
    category: "list7",
    url: "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/drogue/domains",
    format: "domains",
    license: "CC BY-SA 4.0",
    redistributable: true,
    homepage: "https://dsi.ut-capitole.fr/blacklists/",
  },
  {
    id: "blocklistproject-piracy",
    category: "list7",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/piracy.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "ut1-warez",
    category: "list7",
    url: "https://raw.githubusercontent.com/olbat/ut1-blacklists/master/blacklists/warez/domains",
    format: "domains",
    license: "CC BY-SA 4.0",
    redistributable: true,
    homepage: "https://dsi.ut-capitole.fr/blacklists/",
  },
  // UT1's `sect` (144 domains) is deliberately **not** used. It classifies
  // religious movements, and a filter that quietly decides which faiths a
  // household may read about is doing something other than what it says on the
  // box. `astrology` is skipped too, for a duller reason: 28 domains is not a
  // category, it is a rounding error.
  {
    id: "sinfonietta-gambling",
    category: "list2",
    url: "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/gambling-hosts",
    format: "hosts",
    license: "MIT",
    redistributable: true,
    homepage: "https://github.com/Sinfonietta/hostfiles",
  },
  {
    id: "sinfonietta-social",
    category: "list4",
    url: "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/social-hosts",
    format: "hosts",
    license: "MIT",
    redistributable: true,
    homepage: "https://github.com/Sinfonietta/hostfiles",
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

  {
    id: "blocklistproject-malware",
    category: "list1",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/malware.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-phishing",
    category: "list1",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/phishing.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-fraud",
    category: "list1",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/fraud.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-scam",
    category: "list1",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/scam.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-ransomware",
    category: "list1",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/ransomware.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-abuse",
    category: "list1",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/abuse.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-gambling",
    category: "list2",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/gambling.txt",
    format: "hosts",
    license: "Unlicense (public domain)",
    redistributable: true,
    homepage: "https://github.com/blocklistproject/Lists",
  },
  {
    id: "blocklistproject-porn",
    category: "list3",
    url: "https://raw.githubusercontent.com/blocklistproject/Lists/master/porn.txt",
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
    // Wildcards, not plain domains: every line is `*.example.com`. Recorded as
    // "domains" until 2026-07-30, which would have stored the literal
    // "*.example.com" as a blocked domain and matched nothing — harmless only
    // because the source was never enabled. See packages/core/src/feed.ts.
    format: "domainswild",
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
  {
    id: "sjhgvr-oisd",
    category: "list3",
    url: "https://raw.githubusercontent.com/sjhgvr/oisd/main/domainswild2_nsfw.txt",
    format: "domains",
    license: "Same terms as oisd upstream; redistribution restricted",
    redistributable: false,
    homepage: "https://github.com/sjhgvr/oisd",
    note: "A repackaging of oisd, so it inherits oisd's terms — republishing it is no more permitted than republishing the original.",
  },
  {
    id: "firebog",
    category: "list1",
    url: "https://v.firebog.net/hosts/lists.php?type=tick",
    format: "domains",
    license: "N/A — a directory of other lists",
    redistributable: false,
    homepage: "https://firebog.net/",
    note: "Not a blocklist: it is an index of URLs to other people's lists, each with its own licence. Using it means pulling those lists, so the licence question just moves. The permissive ones it points at are already here directly.",
  },
  {
    id: "cisco-talos",
    category: "list1",
    url: "https://talosintelligence.com/documents/ip-blacklist",
    format: "domains",
    license: "Proprietary; redistribution restricted",
    redistributable: false,
    homepage: "https://talosintelligence.com/",
    note: "IP addresses, not domains — a DNS sinkhole cannot act on them, and the terms restrict redistribution regardless.",
  },
];

export const INCLUDED_SOURCES = SOURCES.filter((s) => s.redistributable);
export const EXCLUDED_SOURCES = SOURCES.filter((s) => !s.redistributable);
