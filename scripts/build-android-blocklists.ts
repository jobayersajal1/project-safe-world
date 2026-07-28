/**
 * Exports the bundled per-category domain lists from
 * packages/core/src/blocklists/*.json into the Android :core module's JVM
 * resources as **binary fuse16 filters** over their salted digests.
 *
 * Android does its own matching in Kotlin, so unlike Chrome's
 * declarativeNetRequest and Safari's content blocker — both of which need
 * literal domains in their rule files — it can match on one-way digests. That
 * keeps the list the app ships from being a readable directory of the sites it
 * blocks.
 *
 * A filter rather than a list of digests because Android's list is uncapped:
 * ~4.5M domains is 82 MB as hex digests and worse in memory, against 9.7 MB as
 * a filter. The blocklist only ever asks "is this host listed?" and never needs
 * the list back, which is exactly what an approximate membership structure is
 * for. Every domain that went in is always found — no blocked site can slip
 * through — at the cost of a false positive about 1 lookup in 65,536.
 *
 * The digest and the filter layout must stay byte-identical to
 * `DomainHasher.kt` and `FuseFilter.kt`. `DomainHasherTest` and `FuseFilterTest`
 * pin the same vectors on the Kotlin side, so a change to either fails loudly
 * instead of silently blocking nothing.
 *
 * Run: `npm run build:android`
 */
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { createHash } from "node:crypto";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES } from "../packages/core/src/index.js";
import { normalizeHost } from "../packages/core/src/matcher.js";
import { BinaryFuse32, keyFromHexDigest } from "./binary-fuse.js";

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
/** Written into the sidecar so the reader can reject a layout it can't parse. */
export const FILTER_ALGORITHM = "binary-fuse32-sha256-64";

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

    // De-duplicated because the filter cannot take repeats: construction peels
    // each key exactly once, and a duplicate makes it fail rather than degrade.
    const digests = [...new Set(file.domains.map(hashDomain))].filter(Boolean).sort();
    const keys = digests.map(keyFromHexDigest);

    const filter = BinaryFuse32.build(keys);
    const bytes = filter.serialize();

    const filterPath = join(androidResourcesDir, `${c.id}.filter`);
    await writeFile(filterPath, bytes);

    // The filter cannot be counted or enumerated, so the count travels beside
    // it — the home screen's "we've blocked N websites" reads this.
    const metaPath = join(androidResourcesDir, `${c.id}.json`);
    await writeFile(
      metaPath,
      JSON.stringify(
        {
          category: c.id,
          algorithm: FILTER_ALGORITHM,
          digest: ALGORITHM,
          salt: SALT,
          count: digests.length,
        },
        null,
        2,
      ) + "\n",
    );

    const mb = (bytes.length / 1048576).toFixed(2);
    const bits = ((bytes.length * 8) / digests.length).toFixed(1);
    console.log(`${c.id}: ${digests.length} domains -> ${mb} MB filter (${bits} bits/domain)`);
  }
}

// Only run when invoked directly, so the helpers above can be imported.
if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  build().catch((e) => {
    console.error(e);
    process.exit(1);
  });
}
