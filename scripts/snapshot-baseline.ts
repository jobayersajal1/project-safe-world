/**
 * Records what the apps ship with, so updates can be published as a delta
 * instead of as the whole list.
 *
 * Run this when cutting an app release, right after the lists are final and
 * before building the apps. It snapshots `packages/core/src/blocklists/` into
 * `baseline/`, which stays in this repo and is never published.
 *
 * From then on `npm run build:remote` publishes only the domains added *since*
 * this snapshot. Immediately after a release that delta is empty, and it grows
 * slowly as upstream feeds add entries — so the public repo holds a small,
 * recent slice rather than the whole corpus.
 *
 * Run: `npm run baseline:snapshot`
 */
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES, type CategoryId } from "../packages/core/src/index.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const listsDir = join(repoRoot, "packages/core/src/blocklists");
const baselineDir = join(repoRoot, "baseline");

export interface Baseline {
  /** Short content hash — identifies which snapshot a build shipped with. */
  id: string;
  created: string;
  domains: Record<string, string[]>;
}

export const BASELINE_PATH = join(baselineDir, "baseline.json");

export async function readBaseline(): Promise<Baseline | null> {
  try {
    return JSON.parse(await readFile(BASELINE_PATH, "utf8")) as Baseline;
  } catch {
    return null;
  }
}

async function build(): Promise<void> {
  await mkdir(baselineDir, { recursive: true });

  const domains: Record<string, string[]> = {};
  for (const c of CATEGORIES) {
    const raw = await readFile(join(listsDir, `${c.id}.json`), "utf8");
    domains[c.id] = (JSON.parse(raw) as { domains: string[] }).domains;
  }

  // Hash of the content, so the id changes exactly when the shipped list does.
  const id = createHash("sha256")
    .update(JSON.stringify(domains))
    .digest("hex")
    .slice(0, 12);

  const baseline: Baseline = { id, created: new Date().toISOString().slice(0, 10), domains };
  await writeFile(BASELINE_PATH, JSON.stringify(baseline, null, 2) + "\n");

  const total = Object.values(domains).reduce((n, d) => n + d.length, 0);
  console.log(`baseline ${id}: ${total} domains -> ${BASELINE_PATH}`);
  console.log("");
  console.log("Set this in both apps before building, so each knows what it shipped with:");
  console.log(`  apps/android/.../RemoteConfig.kt   const val LIST_BASELINE = "${id}"`);
  console.log(`  apps/ios/SafeWorld/RemoteConfig.swift   static let listBaseline = "${id}"`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  build().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}

export type { CategoryId };
