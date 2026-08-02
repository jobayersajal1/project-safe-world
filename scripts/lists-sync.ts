/**
 * Moves the blocklists between the private source-of-truth repo and this
 * working tree.
 *
 * The lists are the one thing this project deliberately does not publish. They
 * live scrambled in a private repo; this repo holds only the code that builds
 * against them. Nothing under `packages/core/src/blocklists/` is tracked here —
 * `lists:pull` creates those files, and every build script reads them from
 * there.
 *
 *   npm run lists:pull    private repo -> decoded working files
 *   npm run lists:push    working files -> scrambled -> private repo
 *
 * The scrambling is obfuscation, not encryption: the key ships in every app, so
 * anyone who unpacks one can reverse it. What it buys is that neither the repo
 * nor a glance at a checkout hands over a browsable directory of blocked sites.
 * The privacy comes from the repo being private; this only stops casual reading.
 */
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdir, readFile, readdir, rm, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { gunzipSync, gzipSync } from "node:zlib";
import { CATEGORIES } from "../packages/core/src/categories.js";
import { SCRAMBLE_FORMAT, scrambleDomain, unscrambleDomain } from "./scramble.js";

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const listsDir = join(repoRoot, "packages/core/src/blocklists");
const baselineDir = join(repoRoot, "baseline");

/** Where the private repo is cloned. Outside the repo so it can never be committed. */
const CACHE = join(repoRoot, "..", ".safe-world-lists");
const REMOTE = "https://github.com/jobayersajal1/safe-world-listed.git";

interface ScrambledFile {
  category: string;
  format: string;
  /** Free-text provenance. Never contains a domain. */
  source?: string;
  sources?: unknown;
  updated?: string;
  domains: string[];
}

function git(args: string[], cwd: string): string {
  return execFileSync("git", args, { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
}

/**
 * Lists are stored gzipped, and that is not an optimization — it is what makes
 * the full corpus storable at all.
 *
 * Pretty-printed scrambled JSON runs to about 103 MB for `list1` alone, and
 * GitHub rejects any file over 100 MB. The push failed, so the private repo
 * kept whatever smaller lists it had (20,000 per category, from a capped
 * fetch) — and because `lists:pull` overwrites the working tree, pulling then
 * quietly replaced a 4.5M-domain corpus with a 85k one. A build straight after
 * that produces apps that look healthy and block almost nothing.
 *
 * Gzip takes `list1` to roughly 12 MB, well inside the limit. `.json` is still
 * read when no `.json.gz` is present, so a repo written by an older version of
 * this script still pulls.
 */
function storedListPath(cache: string, id: string): { path: string; gzipped: boolean } | null {
  const gz = join(cache, "lists", `${id}.json.gz`);
  if (existsSync(gz)) return { path: gz, gzipped: true };
  const plain = join(cache, "lists", `${id}.json`);
  if (existsSync(plain)) return { path: plain, gzipped: false };
  return null;
}

async function readStored(entry: { path: string; gzipped: boolean }): Promise<string> {
  const raw = await readFile(entry.path);
  return entry.gzipped ? gunzipSync(raw).toString("utf8") : raw.toString("utf8");
}

/** Clone on first use, otherwise fast-forward. Auth comes from the user's git/gh credentials. */
function syncCache(): void {
  if (!existsSync(CACHE)) {
    console.log(`cloning ${REMOTE}`);
    execFileSync("git", ["clone", "--depth", "1", REMOTE, CACHE], { stdio: "inherit" });
    return;
  }
  git(["fetch", "--depth", "1", "origin", "main"], CACHE);
  git(["reset", "--hard", "origin/main"], CACHE);
}

async function pull(): Promise<void> {
  syncCache();
  await mkdir(listsDir, { recursive: true });

  for (const category of CATEGORIES) {
    const stored = storedListPath(CACHE, category.id);
    if (!stored) {
      throw new Error(`private repo has no list for ${category.id} — run lists:push first`);
    }
    const file = JSON.parse(await readStored(stored)) as ScrambledFile;

    // Refuse a format this build can't reverse rather than writing an empty
    // list: a silently empty blocklist ships an app that blocks nothing while
    // looking perfectly healthy.
    if (file.format !== SCRAMBLE_FORMAT) {
      throw new Error(`${category.id}: unknown format "${file.format}", expected ${SCRAMBLE_FORMAT}`);
    }

    const domains = file.domains.map(unscrambleDomain).filter(Boolean);
    const out = { ...file, format: undefined, domains };
    delete (out as { format?: string }).format;
    await writeFile(join(listsDir, `${category.id}.json`), JSON.stringify(out, null, 2) + "\n");
    console.log(`${category.id}: ${domains.length} domains`);
  }

  const baseline = join(CACHE, "baseline", "baseline.json");
  if (existsSync(baseline)) {
    await mkdir(baselineDir, { recursive: true });
    const file = JSON.parse(await readFile(baseline, "utf8")) as {
      id: string;
      created: string;
      format?: string;
      domains: Record<string, string[]>;
    };
    const domains: Record<string, string[]> = {};
    for (const [key, list] of Object.entries(file.domains)) {
      domains[key] = file.format === SCRAMBLE_FORMAT ? list.map(unscrambleDomain).filter(Boolean) : list;
    }
    await writeFile(
      join(baselineDir, "baseline.json"),
      JSON.stringify({ id: file.id, created: file.created, domains }, null, 2) + "\n",
    );
    console.log(`baseline ${file.id} restored`);
  }
}

async function push(): Promise<void> {
  syncCache();
  await mkdir(join(CACHE, "lists"), { recursive: true });

  for (const category of CATEGORIES) {
    const path = join(listsDir, `${category.id}.json`);
    if (!existsSync(path)) throw new Error(`no working list for ${category.id} — nothing to push`);
    const file = JSON.parse(await readFile(path, "utf8")) as { domains: string[] };
    const out: ScrambledFile = {
      ...(file as object as ScrambledFile),
      category: category.id,
      format: SCRAMBLE_FORMAT,
      domains: file.domains.map(scrambleDomain).filter(Boolean),
    };
    // Not pretty-printed: the indentation was a meaningful fraction of a
    // hundred-megabyte file, and nobody reads a scrambled list by eye anyway.
    const body = gzipSync(Buffer.from(JSON.stringify(out) + "\n", "utf8"), { level: 9 });
    await writeFile(join(CACHE, "lists", `${category.id}.json.gz`), body);
    // Drop the uncompressed file a previous version of this script wrote, so the
    // repo does not carry two copies that can disagree.
    await rm(join(CACHE, "lists", `${category.id}.json`), { force: true });
    console.log(
      `${category.id}: ${out.domains.length} domains scrambled` +
        ` -> ${(body.length / 1048576).toFixed(1)} MB gz`,
    );
  }

  const baselinePath = join(baselineDir, "baseline.json");
  if (existsSync(baselinePath)) {
    await mkdir(join(CACHE, "baseline"), { recursive: true });
    const file = JSON.parse(await readFile(baselinePath, "utf8")) as {
      id: string;
      created: string;
      domains: Record<string, string[]>;
    };
    const domains: Record<string, string[]> = {};
    for (const [key, list] of Object.entries(file.domains)) domains[key] = list.map(scrambleDomain);
    await writeFile(
      join(CACHE, "baseline", "baseline.json"),
      JSON.stringify({ id: file.id, created: file.created, format: SCRAMBLE_FORMAT, domains }, null, 2) + "\n",
    );
  }

  await assertNothingReadable(CACHE);

  git(["add", "-A"], CACHE);
  const status = git(["status", "--porcelain"], CACHE).trim();
  if (status === "") {
    console.log("private repo already up to date");
    return;
  }
  git(["commit", "-m", `Update lists ${new Date().toISOString().slice(0, 10)}`], CACHE);
  git(["push", "origin", "HEAD:main"], CACHE);
  console.log("pushed to the private repo");
}

/**
 * Last line of defence before pushing: if a plaintext domain ever reached the
 * private repo it would sit there indefinitely, so check rather than trust the
 * code above.
 */
async function assertNothingReadable(root: string): Promise<void> {
  const dirs = ["lists", "baseline"];
  for (const dir of dirs) {
    const path = join(root, dir);
    if (!existsSync(path)) continue;
    for (const name of await readdir(path)) {
      // Decompressed before checking, deliberately. Gzip is not obfuscation and
      // this check exists to inspect what is actually being stored — reading the
      // compressed bytes would find no dots in anything and pass every time,
      // turning the last line of defence into a no-op.
      const raw = await readFile(join(path, name));
      const text = name.endsWith(".gz") ? gunzipSync(raw).toString("utf8") : raw.toString("utf8");
      // Scrambled entries are base64 and never contain a dot; a "." inside the
      // domains array means something slipped through unscrambled.
      const parsed = JSON.parse(text) as { domains: unknown };
      const lists = Array.isArray(parsed.domains)
        ? [parsed.domains as string[]]
        : Object.values(parsed.domains as Record<string, string[]>);
      for (const list of lists) {
        const leaked = list.find((d) => d.includes("."));
        if (leaked !== undefined) {
          throw new Error(`${dir}/${name} contains an unscrambled entry ("${leaked}") — refusing to push`);
        }
      }
    }
  }
}

const mode = process.argv[2];
const run = mode === "push" ? push : mode === "pull" ? pull : null;
if (run === null) {
  console.error("usage: lists-sync.ts <pull|push>");
  process.exit(1);
}
run().catch((e) => {
  console.error(e instanceof Error ? e.message : e);
  process.exit(1);
});
