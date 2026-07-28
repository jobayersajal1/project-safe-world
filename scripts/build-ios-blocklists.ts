/**
 * Regenerates the two build artifacts the iOS app ships with, from the
 * bundled per-category domain lists in packages/core/src/blocklists/*.json:
 *
 * 1. Writes the domain lists into the SafeWorldCore Swift package's resource
 *    bundle **scrambled**, so an unpacked .ipa yields no readable list.
 *    `Blocklists.swift` reverses them at runtime.
 * 2. Generates an *empty* default Safari Content Blocker rule list into the
 *    SafeWorldBlocker extension's bundle. It cannot hold the real rules: a
 *    content blocker list must contain literal domains, so shipping a populated
 *    one would put the whole list back in the app in plain text. The extension
 *    serves nothing until the host app runs once and writes the live,
 *    settings-derived list to the shared App Group container.
 *
 * Run: `npm run build:ios`
 */
import { readFile, writeFile, mkdir } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES } from "../packages/core/src/index.js";
import { SCRAMBLE_FORMAT, scrambleDomain } from "./scramble.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const coreListsDir = join(repoRoot, "packages/core/src/blocklists");
const iosResourcesDir = join(
  repoRoot,
  "apps/ios/SafeWorldCore/Sources/SafeWorldCore/Resources",
);
const blockerResourceDir = join(repoRoot, "apps/ios/SafeWorldBlocker/Resources");

interface BlocklistFile {
  category: string;
  source: string;
  domains: string[];
}

async function readCurated(id: string): Promise<BlocklistFile> {
  const raw = await readFile(join(coreListsDir, `${id}.json`), "utf8");
  return JSON.parse(raw) as BlocklistFile;
}

async function build(): Promise<void> {
  await mkdir(iosResourcesDir, { recursive: true });
  await mkdir(blockerResourceDir, { recursive: true });

  for (const c of CATEGORIES) {
    const file = await readCurated(c.id);
    const out = {
      ...file,
      format: SCRAMBLE_FORMAT,
      domains: file.domains.map(scrambleDomain).filter(Boolean),
    };

    const outPath = join(iosResourcesDir, `${c.id}.json`);
    await writeFile(outPath, JSON.stringify(out, null, 2) + "\n");
    console.log(`${c.id}: ${out.domains.length} domains (scrambled) -> ${outPath}`);
  }

  // Deliberately empty — see the note at the top of this file.
  const blockerListPath = join(blockerResourceDir, "blockerList.json");
  await writeFile(blockerListPath, "[]\n");
  console.log(`default content blocker list: empty -> ${blockerListPath}`);
}

build().catch((e) => {
  console.error(e);
  process.exit(1);
});
