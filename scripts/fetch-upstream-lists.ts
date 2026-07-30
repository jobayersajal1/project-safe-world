/**
 * Refreshes `packages/core/src/blocklists/*.json` from the upstream feeds in
 * `sources.ts`, which is where every list this project redistributes comes
 * from.
 *
 * Only sources marked `redistributable` are fetched — we republish a merged
 * list publicly, and several well-known feeds don't permit that. See
 * `sources.ts` for what's excluded and why.
 *
 * Run: `npm run fetch:lists`
 */
import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES, type CategoryId } from "../packages/core/src/categories.js";
import { parseFeed } from "../packages/core/src/feed.js";
import { normalizeHost } from "../packages/core/src/matcher.js";
import { INCLUDED_SOURCES, type Source } from "./sources.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const listsDir = join(repoRoot, "packages/core/src/blocklists");

/**
 * Cap per category. The upstream feeds run to hundreds of thousands of domains
 * each; shipping all of them would mean a multi-megabyte download on every
 * client refresh and would collide with the per-platform rule limits. The feeds
 * are roughly ordered by significance, so taking the head keeps the entries
 * most likely to matter. Raise deliberately, after checking payload size.
 */
const MAX_PER_CATEGORY = Number(process.env.MAX_PER_CATEGORY ?? 20_000);

async function fetchSource(source: Source): Promise<string[]> {
  const res = await fetch(source.url, { redirect: "follow" });
  if (!res.ok) throw new Error(`${source.id}: HTTP ${res.status}`);
  const text = await res.text();

  // Parsing, normalization, and the rejections that matter (bare TLDs, hosts-file
  // placeholders, IP literals, unexpressible URL rules) all live in
  // `packages/core/src/feed.ts`. It used to be duplicated here; the on-device
  // subscription parsers need the same behaviour, and two copies of a parser
  // that must agree byte-for-byte is how they stop agreeing.
  return parseFeed(text, source.format).domains;
}

interface BlocklistFile {
  category: string;
  source: string;
  sources?: { id: string; homepage: string; license: string }[];
  updated?: string;
  domains: string[];
}

async function build(): Promise<void> {
  const byCategory = new Map<CategoryId, Set<string>>();
  const usedByCategory = new Map<CategoryId, Source[]>();

  for (const source of INCLUDED_SOURCES) {
    process.stdout.write(`fetching ${source.id} ... `);
    try {
      const domains = await fetchSource(source);
      const set = byCategory.get(source.category) ?? new Set<string>();
      for (const d of domains) set.add(d);
      byCategory.set(source.category, set);
      usedByCategory.set(source.category, [
        ...(usedByCategory.get(source.category) ?? []),
        source,
      ]);
      console.log(`${domains.length} domains`);
    } catch (e) {
      // One dead feed shouldn't discard every other list; the previous file is
      // left in place for that category.
      console.log(`FAILED (${e instanceof Error ? e.message : String(e)}) — keeping existing list`);
    }
  }

  for (const category of CATEGORIES) {
    const fetched = byCategory.get(category.id);
    if (!fetched || fetched.size === 0) continue;

    const path = join(listsDir, `${category.id}.json`);
    const existing = JSON.parse(await readFile(path, "utf8")) as BlocklistFile;

    // The hand-curated entries always survive a refresh — they're the reason
    // the offline baseline blocks anything sensible if every feed is down.
    const curated = existing.domains.map(normalizeHost).filter(Boolean);
    const merged = [...new Set([...curated, ...fetched])];
    const kept = merged.slice(0, Math.max(MAX_PER_CATEGORY, curated.length));

    const used = usedByCategory.get(category.id) ?? [];
    const file: BlocklistFile = {
      category: category.id,
      source: "curated + upstream feeds",
      sources: used.map((s) => ({ id: s.id, homepage: s.homepage, license: s.license })),
      updated: new Date().toISOString().slice(0, 10),
      domains: kept,
    };

    await writeFile(path, JSON.stringify(file, null, 2) + "\n");
    const dropped = merged.length - kept.length;
    console.log(
      `${category.id}: ${kept.length} domains` +
        (dropped > 0 ? ` (capped, ${dropped} dropped — raise MAX_PER_CATEGORY to keep more)` : ""),
    );
  }
}

build().catch((e) => {
  console.error(e);
  process.exit(1);
});
