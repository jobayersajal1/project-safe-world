import { describe, expect, it } from "vitest";
import {
  GENDER_CONFIDENCE,
  VerdictCache,
  defaultBlurSettings,
  isAnalysisCandidate,
  verdictForFaces,
  withBlurDefaults,
  type FaceObservation,
} from "../src/blur.js";

const sure = (gender: "male" | "female"): FaceObservation => ({ gender, genderProbability: 0.98 });
const unsure = (gender: "male" | "female"): FaceObservation => ({ gender, genderProbability: 0.55 });

describe("verdictForFaces", () => {
  it("leaves an image with no faces alone", () => {
    expect(verdictForFaces([], "women")).toBe("clear");
    expect(verdictForFaces([], "everyone")).toBe("clear");
  });

  it("blurs the target gender and clears the other", () => {
    expect(verdictForFaces([sure("female")], "women")).toBe("blur");
    expect(verdictForFaces([sure("male")], "women")).toBe("clear");
    expect(verdictForFaces([sure("male")], "men")).toBe("blur");
    expect(verdictForFaces([sure("female")], "men")).toBe("clear");
  });

  it("blurs the whole image when any face matches", () => {
    // The unit is the image, not the face: an image with one of each cannot be
    // half-blurred usefully, so a single match takes all of it.
    expect(verdictForFaces([sure("male"), sure("female")], "women")).toBe("blur");
    expect(verdictForFaces([sure("male"), sure("female")], "men")).toBe("blur");
  });

  it("blurs an uncertain face whichever way it was called", () => {
    // This is the rule the whole design leans on. A classifier that is unsure —
    // a side profile, a small thumbnail, a hijab — must not be able to clear an
    // image by guessing.
    expect(verdictForFaces([unsure("male")], "women")).toBe("blur");
    expect(verdictForFaces([unsure("female")], "men")).toBe("blur");
  });

  it("treats the confidence threshold as exclusive", () => {
    const atThreshold: FaceObservation = { gender: "male", genderProbability: GENDER_CONFIDENCE };
    expect(verdictForFaces([atThreshold], "women")).toBe("clear");
    expect(verdictForFaces([{ ...atThreshold, genderProbability: GENDER_CONFIDENCE - 0.001 }], "women"))
      .toBe("blur");
  });

  it("blurs every face for 'everyone', however unsure", () => {
    expect(verdictForFaces([sure("male")], "everyone")).toBe("blur");
    expect(verdictForFaces([unsure("female")], "everyone")).toBe("blur");
  });

  it("honours a caller-supplied confidence", () => {
    expect(verdictForFaces([unsure("male")], "women", 0.5)).toBe("clear");
  });
});

describe("isAnalysisCandidate", () => {
  it("requires both edges to clear the bar", () => {
    expect(isAnalysisCandidate(200, 200, 64)).toBe(true);
    expect(isAnalysisCandidate(64, 64, 64)).toBe(true);
    expect(isAnalysisCandidate(16, 16, 64)).toBe(false);
    // A banner strip is wide and short, and is no more a photograph of a person
    // than a favicon is.
    expect(isAnalysisCandidate(1000, 8, 64)).toBe(false);
    expect(isAnalysisCandidate(8, 1000, 64)).toBe(false);
  });
});

describe("blur settings defaults", () => {
  it("ships off", () => {
    // The feature costs CPU on every image on every page. It starts off and is
    // turned on deliberately, unlike the mandatory blocking categories.
    expect(defaultBlurSettings().enabled).toBe(false);
  });

  it("never reveals until asked", () => {
    expect(defaultBlurSettings().revealMode).toBe("never");
  });

  it("fills in fields a stored blob predates", () => {
    const merged = withBlurDefaults({ enabled: true, target: "men" });
    expect(merged.enabled).toBe(true);
    expect(merged.target).toBe("men");
    expect(merged.strength).toBe(defaultBlurSettings().strength);
    expect(merged.minImagePx).toBe(defaultBlurSettings().minImagePx);
  });

  it("returns defaults for nothing stored", () => {
    expect(withBlurDefaults(undefined)).toEqual(defaultBlurSettings());
    expect(withBlurDefaults(null)).toEqual(defaultBlurSettings());
  });
});

describe("VerdictCache", () => {
  it("returns what it was given", () => {
    const c = new VerdictCache(3);
    c.set("a", "blur");
    expect(c.get("a")).toBe("blur");
    expect(c.get("missing")).toBeUndefined();
  });

  it("evicts the least recently used, not the least recently written", () => {
    const c = new VerdictCache(3);
    c.set("a", "blur");
    c.set("b", "clear");
    c.set("c", "clear");
    c.get("a"); // "a" is now the freshest despite being written first.
    c.set("d", "blur");

    expect(c.size).toBe(3);
    expect(c.get("a")).toBe("blur");
    expect(c.get("b")).toBeUndefined();
    expect(c.get("c")).toBe("clear");
    expect(c.get("d")).toBe("blur");
  });

  it("overwrites without growing", () => {
    const c = new VerdictCache(2);
    c.set("a", "clear");
    c.set("a", "blur");
    expect(c.size).toBe(1);
    expect(c.get("a")).toBe("blur");
  });

  it("stays bounded under a flood", () => {
    const c = new VerdictCache(10);
    for (let i = 0; i < 1000; i++) c.set(`https://cdn.example/${i}.jpg`, "clear");
    expect(c.size).toBe(10);
  });
});
