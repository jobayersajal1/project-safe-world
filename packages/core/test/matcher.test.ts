import { describe, it, expect } from "vitest";
import { normalizeHost, hostMatchesDomain, decide } from "../src/matcher.js";
import { defaultSettings } from "../src/settings.js";
import type { CategoryId } from "../src/categories.js";

function lists(over: Partial<Record<CategoryId, string[]>> = {}) {
  return {
    list1: new Set(over.list1 ?? []),
    list2: new Set(over.list2 ?? ["bet365.com"]),
    list3: new Set(over.list3 ?? ["pornhub.com"]),
  };
}

describe("normalizeHost", () => {
  it("strips scheme, path, port, www, and lowercases", () => {
    expect(normalizeHost("HTTPS://WWW.Bet365.com:443/path?x=1")).toBe("bet365.com");
  });
  it("strips userinfo and trailing dot", () => {
    expect(normalizeHost("http://user:pass@sub.example.com./a")).toBe("sub.example.com");
  });
  it("returns empty for blank input", () => {
    expect(normalizeHost("   ")).toBe("");
  });
});

describe("hostMatchesDomain", () => {
  it("matches exact and subdomains but not siblings", () => {
    expect(hostMatchesDomain("bet365.com", "bet365.com")).toBe(true);
    expect(hostMatchesDomain("m.bet365.com", "bet365.com")).toBe(true);
    expect(hostMatchesDomain("notbet365.com", "bet365.com")).toBe(false);
  });
});

describe("decide", () => {
  it("blocks a listed domain in an enabled category", () => {
    const d = decide("www.bet365.com", defaultSettings(), lists());
    expect(d).toEqual({ blocked: true, reason: "list2" });
  });

  it("blocks subdomains of a listed domain", () => {
    expect(decide("sports.bet365.com", defaultSettings(), lists()).blocked).toBe(true);
  });

  it("does not block when master switch is off", () => {
    const s = { ...defaultSettings(), enabled: false };
    expect(decide("bet365.com", s, lists()).blocked).toBe(false);
  });

  it("does not block when the category is disabled", () => {
    const s = defaultSettings();
    s.categories.list2 = false;
    expect(decide("bet365.com", s, lists()).blocked).toBe(false);
  });

  it("custom allow overrides a category block", () => {
    const s = { ...defaultSettings(), customAllow: ["bet365.com"] };
    expect(decide("bet365.com", s, lists()).blocked).toBe(false);
  });

  it("custom block blocks an otherwise-allowed domain", () => {
    const s = { ...defaultSettings(), customBlock: ["example.com"] };
    expect(decide("app.example.com", s, lists())).toEqual({ blocked: true, reason: "custom" });
  });

  it("allows unlisted domains", () => {
    expect(decide("wikipedia.org", defaultSettings(), lists()).blocked).toBe(false);
  });
});
