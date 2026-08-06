import { existsSync, readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import {
  ADVISORY_CATEGORIES,
  AdvisoryCache,
  HASH_BITS,
  TABLE_SIZE,
  advise,
  advisoryFeatures,
  defaultAdvisorySettings,
  isSharedPlatformHost,
  loadDomainModel,
  scoreHost,
  withAdvisoryDefaults,
  type AdvisorySettings,
  type DomainModel,
} from "../src/advisory.js";

/**
 * The cross-port contract.
 *
 * These scores come from `scripts/export-domain-model.py`, which computed them
 * with the same quantised weights that ship. `DomainModel.kt`, `.swift` and
 * `.cs` pin the same table, so a port that hashes differently, normalises
 * differently, or forgets the L2 step fails here and there rather than shipping
 * a platform that quietly disagrees about the same domain.
 *
 * Every made-up name is absent from all seven blocklists and the real ones are
 * famous sites, so this table publishes nothing.
 */
const PINNED: ReadonlyArray<readonly [string, number, number]> = [
  ["best-casino-slots-bonus.com", 11.653436, -4.603276],
  ["adult-xxx-tube-videos.com", -1.941287, 15.667774],
  ["github.com", -3.003387, -3.731178],
  ["wikipedia.org", -5.504474, -5.090874],
  ["nhs.uk", -3.804730, -5.108463],
  ["acme-plumbing-services.com", -4.638410, -3.460481],
  ["xn--test-punycode-9za.net", -1.502095, -0.603445],
  ["a.b.c.example.co.uk", -5.406568, -2.348093],
  ["3.bp.blogspot.com", -4.672549, 6.294142],
  ["", -4.597354, -3.278166],
];

/** Thresholds baked by the exporter from the reviewed rank, not chosen. */
const THRESHOLDS = { list2: 6.899581, list3: 7.415458 } as const;

function loadShipped(): DomainModel[] | null {
  // Read from disk rather than `import`: the path is dynamic, and bundlers
  // cannot statically analyse that — the import form resolved to nothing and
  // these tests skipped silently, which is worse than not having them.
  //
  // The model files are generated, like every other list-derived artifact here,
  // so a fresh clone does not have them. Skipping beats failing: `npm test` has
  // to stay runnable without the private lists.
  const out: DomainModel[] = [];
  for (const category of ADVISORY_CATEGORIES) {
    const path = new URL(`../src/model/${category}.json`, import.meta.url);
    if (!existsSync(path)) return null;
    out.push(loadDomainModel(JSON.parse(readFileSync(path, "utf8"))));
  }
  return out;
}

describe("advisoryFeatures", () => {
  it("is L2-normalised", () => {
    for (const host of ["bet365.com", "a.b.c.example.co.uk", "x.io"]) {
      let sum = 0;
      for (const v of advisoryFeatures(host).values()) sum += v * v;
      expect(sum).toBeCloseTo(1, 10);
    }
  });

  it("indexes stay inside the table", () => {
    for (const [idx] of advisoryFeatures("some-long-hyphenated-name-99.example.co.uk")) {
      expect(idx).toBeGreaterThanOrEqual(0);
      expect(idx).toBeLessThan(TABLE_SIZE);
    }
    expect(TABLE_SIZE).toBe(1 << HASH_BITS);
  });

  it("ignores a leading www. and case, like normalizeHost", () => {
    const a = [...advisoryFeatures("Example.COM")].sort();
    const b = [...advisoryFeatures("www.example.com")].sort();
    expect(a).toEqual(b);
  });

  it("returns nothing for a host with no features", () => {
    expect(advisoryFeatures("").size).toBe(0);
    expect(advisoryFeatures("   ").size).toBe(0);
  });

  it("distinguishes a label boundary from the same letters mid-label", () => {
    // The dots and the ^/$ markers exist so `bet` at the start of a label is a
    // different feature from `bet` inside one — bet365 versus alphabet.
    const bet = advisoryFeatures("bet.example");
    const alphabet = advisoryFeatures("alphabet.example");
    expect([...bet.keys()].some((k) => !alphabet.has(k))).toBe(true);
  });
});

describe("isSharedPlatformHost", () => {
  it("covers the Blogger CDN that made this guard necessary", () => {
    // The adult model learned *.blogspot.com and then flagged the image host
    // that serves the pictures on every Blogger blog there is.
    expect(isSharedPlatformHost("1.bp.blogspot.com")).toBe(true);
    expect(isSharedPlatformHost("3.bp.blogspot.com")).toBe(true);
    expect(isSharedPlatformHost("videoseriesbiblicas.blogspot.com")).toBe(true);
  });

  it("covers other shared hosting, and nothing else", () => {
    expect(isSharedPlatformHost("someone.github.io")).toBe(true);
    expect(isSharedPlatformHost("shop.myshopify.com")).toBe(true);
    expect(isSharedPlatformHost("github.com")).toBe(false);
    expect(isSharedPlatformHost("blogspot.com.evil.example")).toBe(false);
  });
});

describe("settings", () => {
  it("ships off — this is a guess, so opting in is a choice", () => {
    expect(defaultAdvisorySettings().enabled).toBe(false);
  });

  it("fills gaps in a stored value without dropping what it had", () => {
    const merged = withAdvisoryDefaults({ enabled: true, categories: { list3: false } });
    expect(merged.enabled).toBe(true);
    expect(merged.categories.list3).toBe(false);
    expect(merged.categories.list2).toBe(true);
  });

  it("survives a null or malformed stored value", () => {
    expect(withAdvisoryDefaults(null)).toEqual(defaultAdvisorySettings());
    expect(withAdvisoryDefaults(undefined)).toEqual(defaultAdvisorySettings());
  });
});

describe("advise", () => {
  const on: AdvisorySettings = { enabled: true, categories: { list2: true, list3: true } };
  const fake = (category: "list2" | "list3", threshold: number): DomainModel => ({
    category,
    scale: 1,
    bias: 0,
    threshold,
    // One weight set high so a known host clears the bar deterministically,
    // without depending on the generated model being present.
    weights: new Int8Array(TABLE_SIZE),
  });

  it("says nothing at all when disabled", () => {
    const models = [fake("list2", -1e9)];
    expect(advise("anything.example", models, { ...on, enabled: false })).toEqual({ advise: false });
  });

  it("skips a category the user turned off", () => {
    const models = [fake("list2", -1e9)];
    expect(advise("x.example", models, { enabled: true, categories: { list2: false } })).toEqual({
      advise: false,
    });
  });

  it("never advises against a shared platform, whatever the score", () => {
    const models = [fake("list3", -1e9)];
    expect(advise("anyone.blogspot.com", models, on)).toEqual({ advise: false });
  });

  it("honours an injected popular-sites allowlist", () => {
    const models = [fake("list2", -1e9)];
    expect(advise("famous.example", models, on, { isWellKnown: () => true })).toEqual({
      advise: false,
    });
  });

  it("returns the first enabled category to clear its threshold", () => {
    const models = [fake("list2", -1e9), fake("list3", -1e9)];
    expect(advise("x.example", models, on)).toMatchObject({ advise: true, category: "list2" });
  });

  it("says nothing for an empty host", () => {
    expect(advise("", [fake("list2", -1e9)], on)).toEqual({ advise: false });
  });
});

describe("AdvisoryCache", () => {
  it("computes once per host", () => {
    const cache = new AdvisoryCache();
    let calls = 0;
    const compute = (): { advise: false } => {
      calls++;
      return { advise: false };
    };
    cache.get("a.example", compute);
    cache.get("a.example", compute);
    cache.get("b.example", compute);
    expect(calls).toBe(2);
  });

  it("evicts the least recently used, not the oldest inserted", () => {
    const cache = new AdvisoryCache(2);
    let calls = 0;
    const compute = (): { advise: false } => {
      calls++;
      return { advise: false };
    };
    cache.get("a", compute);
    cache.get("b", compute);
    cache.get("a", compute); // refreshes a, so b is now the eviction candidate
    cache.get("c", compute); // evicts b
    expect(calls).toBe(3);
    cache.get("a", compute);
    expect(calls).toBe(3);
  });
});

describe("the shipped models", () => {
  it("agree with the exporter, to the digit every port pins", () => {
    const models = loadShipped();
    if (!models) return; // generated artifact absent — see loadShipped
    const [list2, list3] = models as [DomainModel, DomainModel];
    for (const [host, expected2, expected3] of PINNED) {
      expect(scoreHost(list2, host), `list2 ${host}`).toBeCloseTo(expected2, 4);
      expect(scoreHost(list3, host), `list3 ${host}`).toBeCloseTo(expected3, 4);
    }
    expect(list2.threshold).toBeCloseTo(THRESHOLDS.list2, 6);
    expect(list3.threshold).toBeCloseTo(THRESHOLDS.list3, 6);
  });

  it("warns about names of the right shape and leaves ordinary sites alone", () => {
    const models = loadShipped();
    if (!models) return;
    const on: AdvisorySettings = { enabled: true, categories: { list2: true, list3: true } };

    expect(advise("best-casino-slots-bonus.com", models, on)).toMatchObject({
      advise: true,
      category: "list2",
    });
    expect(advise("adult-xxx-tube-videos.com", models, on)).toMatchObject({
      advise: true,
      category: "list3",
    });

    // The sites where one wrong interstitial ends the user's trust in all of it.
    for (const host of [
      "github.com",
      "wikipedia.org",
      "nhs.uk",
      "chase.com",
      "sciencedirect.com",
      "gov.uk",
      "who.int",
      "acme-plumbing-services.com",
    ]) {
      expect(advise(host, models, on), host).toEqual({ advise: false });
    }
  });
});
