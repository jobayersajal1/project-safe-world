/**
 * Builds the files published to the public lists repo.
 *
 * These carry only the **delta** — domains added since the snapshot in
 * `baseline/`, which is what the apps ship with. The full list lives in this
 * repo and inside the app bundle; the public repo never sees it. Right after a
 * release the delta is empty, and it grows slowly as upstream feeds add
 * entries, so what's exposed publicly is a small recent slice rather than the
 * whole corpus.
 *
 * That works because remote updates are additive: bundled lists are the offline
 * baseline and remote never replaces them, so "bundled ∪ delta" is the complete
 * list on device.
 *
 *   delta-<baseline>-android.json   salted SHA-256 digests, matched one-way in Kotlin
 *   delta-<baseline>.json           XOR-scrambled domains for iOS and Chrome
 *
 * The file names carry the baseline id so an app only ever fetches a delta
 * computed against the list it actually shipped with. An app whose baseline has
 * no published delta gets a 404, which degrades to "no new domains" rather than
 * to wrong ones.
 *
 * Run: `npm run build:remote`
 */
import { createHash } from "node:crypto";
import { writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES } from "../packages/core/src/index.js";
import { hashDomain, readCurated } from "./build-android-blocklists.js";
import { scrambleDomain, SCRAMBLE_FORMAT } from "./scramble.js";
import { readBaseline } from "./snapshot-baseline.js";

const here = dirname(fileURLToPath(import.meta.url));
const outDir = join(dirname(here), "dist/remote");

/** Bumped when the payload *shape* changes, not on every list edit. */
const PAYLOAD_VERSION = 1;

async function build(): Promise<void> {
  await mkdir(outDir, { recursive: true });

  const baseline = await readBaseline();
  if (!baseline) {
    console.error(
      "No baseline/baseline.json. Run `npm run baseline:snapshot` first — without it\n" +
        "there is nothing to diff against, and publishing the full list is exactly what\n" +
        "the delta scheme exists to avoid.",
    );
    process.exit(1);
  }

  const hashed: Record<string, string[]> = {};
  const scrambled: Record<string, string[]> = {};
  let added = 0;
  let shipped = 0;

  for (const c of CATEGORIES) {
    const current = await readCurated(c.id);
    const already = new Set(baseline.domains[c.id] ?? []);
    shipped += already.size;

    const newDomains = current.domains.filter((d) => !already.has(d));
    added += newDomains.length;

    hashed[c.id] = [...new Set(newDomains.map(hashDomain))].filter(Boolean).sort();
    scrambled[c.id] = newDomains.map(scrambleDomain).filter(Boolean).sort();
  }

  // Identifies this exact set of domains so a client can tell "I already have
  // this" from "this is new" without diffing the lists. Derived from the content
  // rather than a timestamp, so republishing an unchanged list keeps the same id
  // and every client correctly skips it.
  const updateId = (domains: Record<string, string[]>): string =>
    createHash("sha256").update(JSON.stringify(domains)).digest("hex").slice(0, 12);

  const androidPath = join(outDir, `delta-${baseline.id}-android.json`);
  await writeFile(
    androidPath,
    JSON.stringify(
      {
        version: PAYLOAD_VERSION,
        baseline: baseline.id,
        updateId: updateId(hashed),
        format: "sha256-128-hex",
        domains: hashed,
      },
      null,
      2,
    ) + "\n",
  );

  const otherPath = join(outDir, `delta-${baseline.id}.json`);
  await writeFile(
    otherPath,
    JSON.stringify(
      {
        version: PAYLOAD_VERSION,
        baseline: baseline.id,
        updateId: updateId(scrambled),
        format: SCRAMBLE_FORMAT,
        domains: scrambled,
      },
      null,
      2,
    ) + "\n",
  );

  console.log(`baseline ${baseline.id} (${baseline.created}): ${shipped} domains in the app`);
  console.log(`delta: ${added} added since`);
  console.log(`  hashed    -> ${androidPath}`);
  console.log(`  scrambled -> ${otherPath}`);
  if (added === 0) {
    console.log("\nEmpty delta — the apps already ship everything. Nothing to publish yet.");
  }
}

build().catch((e) => {
  console.error(e);
  process.exit(1);
});
