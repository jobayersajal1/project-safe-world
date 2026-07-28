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
 * Safari's content blocker refuses a rule list beyond roughly 150,000 rules,
 * and `BlockerListBuilder` puts a whole category into one rule's `if-domain`
 * array. The array length is where this actually binds, and Apple does not
 * document a number for it — 50,000 is a deliberately conservative starting
 * point until it is tested on a device.
 */
const MAX_PER_CATEGORY = Number(process.env.IOS_MAX_PER_CATEGORY ?? 50_000);

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
    const kept = file.domains.slice(0, MAX_PER_CATEGORY);
    const out = {
      ...file,
      format: SCRAMBLE_FORMAT,
      domains: kept.map(scrambleDomain).filter(Boolean),
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
