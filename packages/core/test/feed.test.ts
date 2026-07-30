import { describe, it, expect } from "vitest";
import { parseFeedLine, parseFeed, type FeedFormat } from "../src/feed.js";

/**
 * The cross-platform contract for feed parsing.
 *
 * `FeedParser.kt` pins these same vectors. That matters more here than it looks:
 * the app downloads a third-party file at runtime and stores what this produces,
 * so a parser that silently yields nothing gives a subscription that reports
 * "enabled" and blocks not one extra domain.
 */
export const VECTORS: ReadonlyArray<readonly [FeedFormat, string, string | null]> = [
  // hosts
  ["hosts", "0.0.0.0 example.com", "example.com"],
  ["hosts", "127.0.0.1 example.com # inline comment", "example.com"],
  ["hosts", "0.0.0.0 localhost", null],
  ["hosts", "example.com", null], // one field is not a hosts line

  // domains
  ["domains", "example.com", "example.com"],
  ["domains", "example.com   ", "example.com"],

  // domainswild — oisd
  ["domainswild", "*.example.com", "example.com"],
  ["domainswild", "example.com", "example.com"],
  ["domainswild", "*.sub.example.com", "sub.example.com"],

  // adblock — hagezi
  ["adblock", "||example.com^", "example.com"],
  ["adblock", "||example.com", "example.com"],
  ["adblock", "[Adblock Plus]", null],
  ["adblock", "! Title: something", null],
  ["adblock", "@@||example.com^", null], // exception: cannot express "don't block"
  ["adblock", "||example.com^$third-party", null], // modifier is URL-level
  ["adblock", "||example.com/path^", null],
  ["adblock", "/banner\\d+/", null], // regex rule

  // shared rejections
  ["domains", "", null],
  ["domains", "# comment", null],
  ["domains", "! comment", null],
  ["domains", "com", null], // bare TLD would block everything under it
  ["domains", "1.2.3.4", null], // an address, not a name
  ["domainswild", "*", null],

  // Bare adult/gambling TLDs, which both feeds publish on purpose. These are
  // the five lines the live feeds lost before `BLOCKABLE_TLDS` existed.
  ["adblock", "||www.xxx^", "xxx"],
  ["domainswild", "*.porn", "porn"],
  ["domainswild", "*.sex", "sex"],
  ["domainswild", "*.adult", "adult"],
  ["domainswild", "*.xxx", "xxx"],
];

describe("parseFeedLine", () => {
  for (const [format, line, expected] of VECTORS) {
    it(`${format}: ${JSON.stringify(line)} -> ${JSON.stringify(expected)}`, () => {
      expect(parseFeedLine(line, format)).toBe(expected);
    });
  }

  it("lowercases and strips www., matching normalizeHost", () => {
    expect(parseFeedLine("0.0.0.0 WWW.Example.COM", "hosts")).toBe("example.com");
  });

  /**
   * The one that would be catastrophic rather than merely wrong: a bare TLD
   * stored as a blocked domain makes `hostMatchesDomain` match every host under
   * it, so a single stray line takes out every .com.
   */
  it("never yields a general-purpose bare TLD, however it is written", () => {
    const dangerous = ["com", "net", "org", "io", "co", "uk", "de", "app", "dev"];
    for (const tld of dangerous) {
      for (const line of [tld, `*.${tld}`, `||${tld}^`, `0.0.0.0 ${tld}`, `  ${tld}  `]) {
        for (const format of ["hosts", "domains", "domainswild", "adblock"] as FeedFormat[]) {
          expect(parseFeedLine(line, format)).toBeNull();
        }
      }
    }
  });

  /**
   * The deliberate exception. Blocking all of `.xxx` is what an adult-content
   * blocker is for, and both upstreams publish it, so dropping those lines lost
   * the most valuable entries in the file.
   */
  it("does yield the adult and gambling TLDs, which upstreams block whole", () => {
    for (const tld of ["xxx", "porn", "sex", "adult", "casino", "bet", "poker"]) {
      expect(parseFeedLine(`*.${tld}`, "domainswild")).toBe(tld);
      expect(parseFeedLine(`||${tld}^`, "adblock")).toBe(tld);
    }
  });
});

describe("parseFeed", () => {
  it("de-duplicates and preserves first-seen order", () => {
    const { domains } = parseFeed("b.com\na.com\nb.com\n", "domains");
    expect(domains).toEqual(["b.com", "a.com"]);
  });

  it("does not count comments or blanks as skipped", () => {
    const { domains, skipped } = parseFeed("# c\n\n!x\n[Adblock Plus]\na.com\n", "domains");
    expect(domains).toEqual(["a.com"]);
    expect(skipped).toBe(0);
  });

  /**
   * "0 domains, many skipped" means the format is wrong; "0 and 0" means the
   * body was empty. Collapsing those two would make a misconfigured
   * subscription indistinguishable from a failed download.
   */
  it("counts unusable lines so a wrong format is diagnosable", () => {
    const wrongFormat = parseFeed("||a.com^\n||b.com^\n", "hosts");
    expect(wrongFormat.domains).toEqual([]);
    expect(wrongFormat.skipped).toBe(2);

    const empty = parseFeed("", "hosts");
    expect(empty.domains).toEqual([]);
    expect(empty.skipped).toBe(0);
  });

  it("parses a realistic hagezi header plus entries", () => {
    const feed = [
      "[Adblock Plus]",
      "! Title: HaGeZi's NSFW DNS Blocklist",
      "! Number of entries: 108676",
      "!",
      "||explorer.01coin.io^",
      "||eromitai.2chblog.jp^",
    ].join("\n");

    const { domains, skipped } = parseFeed(feed, "adblock");
    expect(domains).toEqual(["explorer.01coin.io", "eromitai.2chblog.jp"]);
    expect(skipped).toBe(0);
  });

  it("parses a realistic oisd header plus wildcard entries", () => {
    const feed = [
      "# Version: 202607300106",
      "# Syntax: Domains (wildcards)",
      "",
      "*.0-0.asia",
      "*.0-porno.com",
    ].join("\n");

    const { domains } = parseFeed(feed, "domainswild");
    expect(domains).toEqual(["0-0.asia", "0-porno.com"]);
  });
});
