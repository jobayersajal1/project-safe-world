/**
 * Writes the per-category domain lists into the Windows app in two forms.
 *
 * 1. **Scrambled** embedded resources for the hosts-file path, capped because a hosts file is
 *    parsed linearly by the DNS Client service. `Blocklists.cs` reverses them at runtime.
 * 2. **Uncapped** binary fuse filters for the local DNS proxy, which matches in our own code and
 *    so has no such ceiling. Same files Android and iOS ship, byte for byte.
 *
 * Run: `npm run build:windows`
 */
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES } from "../packages/core/src/index.js";
import { SCRAMBLE_FORMAT, scrambleDomain } from "./scramble.js";
import { BinaryFuse32, keyFromHexDigest } from "./binary-fuse.js";
import { hashDomain } from "./build-android-blocklists.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const coreListsDir = join(repoRoot, "packages/core/src/blocklists");
const windowsResourcesDir = join(repoRoot, "apps/windows/SafeWorld.Core/Resources");
const filtersDir = join(repoRoot, "apps/windows/SafeWorld.App/filters");

/**
 * How many domains this platform can actually take.
 *
 * Android is uncapped because its matching is ours: a binary fuse filter holds
 * millions of domains in ~19 MB. Every other platform delegates matching to an
 * engine that needs the literal domains, and each has its own ceiling — so they
 * take the head of the list, which is ordered with the highest-signal feeds
 * first.
 */
/**
 * Windows writes every blocked domain into the system `hosts` file, which the
 * DNS Client service parses linearly. Large hosts files are a long-reported
 * cause of slow boot and high CPU there. 50,000 per category (~250k lines,
 * ~7 MB) is the conservative starting point; raise it from a measurement, not
 * from an assumption.
 */
const MAX_PER_CATEGORY = Number(process.env.WINDOWS_MAX_PER_CATEGORY ?? 50_000);

async function build(): Promise<void> {
  await mkdir(windowsResourcesDir, { recursive: true });

  for (const c of CATEGORIES) {
    const raw = await readFile(join(coreListsDir, `${c.id}.json`), "utf8");
    const file = JSON.parse(raw) as { domains: string[] };
    const kept = file.domains.slice(0, MAX_PER_CATEGORY);
    const out = {
      ...file,
      format: SCRAMBLE_FORMAT,
      domains: kept.map(scrambleDomain).filter(Boolean),
    };

    const outPath = join(windowsResourcesDir, `${c.id}.json`);
    await writeFile(outPath, JSON.stringify(out, null, 2) + "\n");
    console.log(`${c.id}: ${out.domains.length} domains (scrambled) -> ${outPath}`);
  }

  // Uncapped filters for the DNS proxy. Copied next to the executable at publish time rather
  // than embedded: they are memory-mapped, which needs a real file on disk.
  await mkdir(filtersDir, { recursive: true });
  for (const c of CATEGORIES) {
    const raw = await readFile(join(coreListsDir, `${c.id}.json`), "utf8");
    const file = JSON.parse(raw) as { domains: string[] };
    const digests = [...new Set(file.domains.map(hashDomain))].filter(Boolean).sort();
    const filter = BinaryFuse32.build(digests.map(keyFromHexDigest));
    const bytes = filter.serialize();
    await writeFile(join(filtersDir, `${c.id}.filter`), bytes);
    console.log(
      `${c.id}: ${digests.length} domains -> ${(bytes.length / 1048576).toFixed(2)} MB filter`,
    );
  }
}

build().catch((e) => {
  console.error(e);
  process.exit(1);
});
