/**
 * Writes the per-category domain lists into the Chrome extension, **scrambled**.
 *
 * Run: `npm run build:lists`
 *
 * This used to generate static `declarativeNetRequest` rule files. It can't any
 * more: a static ruleset is a JSON file of literal domains inside the extension,
 * so shipping one would put the entire list in readable form into every
 * downloaded `.zip`. Instead the extension bundles these scrambled lists and
 * builds the equivalent rules as **dynamic** rules at runtime, after unscrambling
 * them in memory — see `apps/chrome-extension/src/background.ts`.
 *
 * Dynamic rules are a real substitute rather than a workaround: they match
 * identically, they persist across browser restarts, and at `DOMAINS_PER_RULE`
 * = 1000 the whole corpus packs into well under Chrome's 30,000-rule ceiling.
 *
 * The output is gitignored. `npm run lists:pull` has to have run first.
 */
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES } from "../packages/core/src/index.js";
import { SCRAMBLE_FORMAT, scrambleDomain } from "./scramble.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const coreListsDir = join(repoRoot, "packages/core/src/blocklists");
// Vite copies `public/` verbatim into `dist/`, so these ship as separate files
// rather than being inlined into the service-worker bundle — 85k base64 entries
// baked into JS would bloat the worker and be parsed on every startup.
const extListsDir = join(repoRoot, "apps/chrome-extension/public/lists");

async function build(): Promise<void> {
  await mkdir(extListsDir, { recursive: true });

  for (const c of CATEGORIES) {
    const raw = await readFile(join(coreListsDir, `${c.id}.json`), "utf8");
    const file = JSON.parse(raw) as { domains: string[] };
    const domains = [...new Set(file.domains)].sort();

    const out = {
      category: c.id,
      format: SCRAMBLE_FORMAT,
      domains: domains.map(scrambleDomain).filter(Boolean),
    };

    const outPath = join(extListsDir, `${c.id}.json`);
    // Not pretty-printed: this is a build artifact nobody reads, and one entry
    // per line would triple the size of the packaged extension.
    await writeFile(outPath, JSON.stringify(out) + "\n");
    console.log(`${c.id}: ${out.domains.length} domains (scrambled) -> ${outPath}`);
  }
}

build().catch((e) => {
  console.error(e);
  process.exit(1);
});
