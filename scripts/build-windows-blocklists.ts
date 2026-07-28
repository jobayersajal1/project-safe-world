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

async function build(): Promise<void> {
  await mkdir(windowsResourcesDir, { recursive: true });

  for (const c of CATEGORIES) {
    const raw = await readFile(join(coreListsDir, `${c.id}.json`), "utf8");
    const file = JSON.parse(raw) as { domains: string[] };
    const out = {
      ...file,
      format: SCRAMBLE_FORMAT,
      domains: file.domains.map(scrambleDomain).filter(Boolean),
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
