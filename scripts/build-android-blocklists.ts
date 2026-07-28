/**
 * Exports the bundled per-category domain lists from
 * packages/core/src/blocklists/*.json into the Android :core module's JVM
 * resources, **hashed** rather than as plaintext domains.
 *
 * Android does its own matching in Kotlin, so unlike Chrome's
 * declarativeNetRequest and Safari's content blocker — both of which need
 * literal domains in their rule files — it can match on one-way digests. That
 * keeps the list the app ships from being a readable directory of the sites it
 * blocks.
 *
 * The digest here must stay byte-identical to
 * `apps/android/core/src/main/kotlin/com/safeworld/core/DomainHasher.kt`.
 * `DomainHasherTest` pins the same vector on the Kotlin side, so a change to
 * either fails loudly instead of silently blocking nothing.
 *
 * Run: `npm run build:android`
 */
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES } from "../packages/core/src/index.js";
import { normalizeHost } from "../packages/core/src/matcher.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const coreListsDir = join(repoRoot, "packages/core/src/blocklists");
const androidResourcesDir = join(
  repoRoot,
  "apps/android/core/src/main/resources/blocklists",
);

/** Must match DomainHasher.SALT / DIGEST_BYTES and Blocklists.EXPECTED_ALGORITHM. */
export const SALT = "safe-world:v1:";
export const DIGEST_BYTES = 16;
export const ALGORITHM = "sha256-128-hex";

interface BlocklistFile {
  category: string;
  source: string;
  domains: string[];
}

export function hashDomain(domain: string): string {
  const normalized = normalizeHost(domain);
  if (normalized === "") return "";
  return createHash("sha256")
    .update(SALT + normalized, "utf8")
    .digest("hex")
    .slice(0, DIGEST_BYTES * 2);
}

export async function readCurated(id: string): Promise<BlocklistFile> {
  const raw = await readFile(join(coreListsDir, `${id}.json`), "utf8");
  return JSON.parse(raw) as BlocklistFile;
}

async function build(): Promise<void> {
  await mkdir(androidResourcesDir, { recursive: true });

  for (const c of CATEGORIES) {
    const file = await readCurated(c.id);

    // Sorted and de-duplicated so the generated file is stable across runs and
    // diffs cleanly.
    const hashes = [...new Set(file.domains.map(hashDomain))].filter(Boolean).sort();

    const outPath = join(androidResourcesDir, `${c.id}.json`);
    await writeFile(
      outPath,
      JSON.stringify({ category: c.id, algorithm: ALGORITHM, salt: SALT, hashes }, null, 2) + "\n",
    );
    console.log(`${c.id}: ${file.domains.length} domains -> ${hashes.length} hashes -> ${outPath}`);
  }
}

// Only run when invoked directly, so the helpers above can be imported.
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  build().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
