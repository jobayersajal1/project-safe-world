import { describe, expect, it } from "vitest";
import {
  HASHED_FORMAT,
  SCRAMBLE_FORMAT,
  plainDomains,
  scrambleDomain,
  unscrambleDomain,
} from "../src/scramble.js";
import type { RemoteUpdatePayload } from "../src/types.js";

/**
 * These vectors are duplicated in the Swift decoder
 * (apps/ios/SafeWorldCore/Tests/.../ScrambleTests.swift). They are the contract
 * between the generator and every client: if the key, encoding, or
 * normalization drifts on either side, a client fetches a list it decodes to
 * garbage and blocks nothing with — a silent failure without these.
 */
const VECTORS: ReadonlyArray<readonly [string, string]> = [
  ["bet365.com", "EQQSVhtCQREDCQ=="],
  ["pornhub.com", "Aw4UC0UCDVwPC0A="],
  ["example.com", "FhkHCF0bClwPC0A="],
];

describe("scrambleDomain", () => {
  it("produces the pinned values shared with the Swift decoder", () => {
    for (const [plain, scrambled] of VECTORS) {
      expect(scrambleDomain(plain)).toBe(scrambled);
    }
  });

  it("round-trips", () => {
    for (const [plain] of VECTORS) {
      expect(unscrambleDomain(scrambleDomain(plain))).toBe(plain);
    }
  });

  it("normalizes case and whitespace before scrambling", () => {
    expect(scrambleDomain("  BET365.com  ")).toBe(scrambleDomain("bet365.com"));
  });

  it("does not leave the domain readable", () => {
    expect(scrambleDomain("bet365.com")).not.toContain("bet365");
  });

  it("handles empty input", () => {
    expect(scrambleDomain("")).toBe("");
    expect(unscrambleDomain("")).toBe("");
  });
});

describe("plainDomains", () => {
  const payload = (over: Partial<RemoteUpdatePayload>): RemoteUpdatePayload => ({
    version: 1,
    domains: {},
    ...over,
  });

  it("unscrambles a scrambled payload", () => {
    const p = payload({
      format: SCRAMBLE_FORMAT,
      domains: { gambling: ["EQQSVhtCQREDCQ=="] },
    });
    expect(plainDomains(p)).toEqual({ gambling: ["bet365.com"] });
  });

  it("treats a payload with no format as plaintext, so older ones keep working", () => {
    const p = payload({ domains: { gambling: ["bet365.com"] } });
    expect(plainDomains(p)).toEqual({ gambling: ["bet365.com"] });
  });

  it("refuses Android's hashed list instead of installing digests as domains", () => {
    const p = payload({
      format: HASHED_FORMAT,
      domains: { gambling: ["d658ebf1a97d66b19cc925ece45b6255"] },
    });
    expect(() => plainDomains(p)).toThrow(/hashed for Android/);
  });

  it("refuses an unknown format", () => {
    expect(() => plainDomains(payload({ format: "rot13-v9" }))).toThrow(/Unrecognized/);
  });
});
