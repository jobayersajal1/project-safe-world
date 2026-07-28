/**
 * Writes the per-category domain lists from packages/core/src/blocklists/*.json into the
 * SafeWorld.Core project's embedded resources, **scrambled**, so the published .exe yields no
 * readable list. `Blocklists.cs` reverses them at runtime via `Scramble.cs`.
 *
 * Run: `npm run build:windows`
 */
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES } from "../packages/core/src/index.js";
import { SCRAMBLE_FORMAT, scrambleDomain } from "./scramble.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const coreListsDir = join(repoRoot, "packages/core/src/blocklists");
const windowsResourcesDir = join(repoRoot, "apps/windows/SafeWorld.Core/Resources");

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
}

build().catch((e) => {
  console.error(e);
  process.exit(1);
});
