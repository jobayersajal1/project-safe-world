/**
 * Fails the build when the blocklists haven't been pulled.
 *
 * The lists are not in this repo — they live in the private source repo and
 * arrive via `npm run lists:pull`. Without this check, building a fresh clone
 * produces artifacts with **no domains in them**: every script would happily
 * write an empty list, every test that only asserts "the file parses" would
 * pass, and the result would be an app that installs, runs, reports itself
 * healthy, and blocks nothing.
 *
 * That is the worst failure this project can have, so it is worth one loud
 * error up front.
 */
import { existsSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { CATEGORIES } from "../packages/core/src/categories.js";

const here = dirname(fileURLToPath(import.meta.url));
const listsDir = join(here, "..", "packages/core/src/blocklists");

const missing: string[] = [];
const empty: string[] = [];

for (const category of CATEGORIES) {
  const path = join(listsDir, `${category.id}.json`);
  if (!existsSync(path)) {
    missing.push(category.id);
    continue;
  }
  const file = JSON.parse(readFileSync(path, "utf8")) as { domains?: string[] };
  if (!file.domains?.length) empty.push(category.id);
}

if (missing.length > 0 || empty.length > 0) {
  const detail = [
    missing.length > 0 ? `missing: ${missing.join(", ")}` : "",
    empty.length > 0 ? `empty: ${empty.join(", ")}` : "",
  ]
    .filter(Boolean)
    .join("; ");

  console.error(
    `\nBlocklists are not available (${detail}).\n\n` +
      "They are deliberately not stored in this repo. Run:\n\n" +
      "    npm run lists:pull\n\n" +
      "which needs read access to the private repo jobayersajal1/safe-world-listed.\n" +
      "Building without them would produce apps that block nothing.\n",
  );
  process.exit(1);
}

console.log(`blocklists present: ${CATEGORIES.length} categories`);
