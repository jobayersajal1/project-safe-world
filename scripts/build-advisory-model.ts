import { copyFile, mkdir, readdir, stat } from "node:fs/promises";
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { ADVISORY_CATEGORIES } from "../packages/core/src/advisory.js";

/**
 * Copy the exported advisory models into the extension's packaged files.
 *
 * They go to `public/model/` rather than being imported, for the same reason
 * the blocklists do: each is 341 KB of base64, and importing both would inline
 * 682 KB into the service worker's bundle. `background.ts` fetches them with
 * `chrome.runtime.getURL`.
 *
 * Android gets the same files as `:core` resources, which are on the classpath
 * for `:core:test` and packaged into the APK — the path `Blocklists` already
 * uses for the fuse filters.
 *
 * The models themselves come from `scripts/export-domain-model.py`, which needs
 * the trained weights and therefore the private lists. This step only moves
 * them, so it is a no-op — not an error — when they are absent: an extension
 * built without them behaves exactly like one from before the feature existed.
 */

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = join(here, "..");
const modelDir = join(repoRoot, "packages", "core", "src", "model");
const chromeDir = join(repoRoot, "apps", "chrome-extension", "public", "model");
// Kotlin reads it off the classpath, exactly as `Blocklists` reads the filters,
// so `:core:test` and the packaged APK take the same path.
const androidDir = join(repoRoot, "apps", "android", "core", "src", "main", "resources", "model");

async function build(): Promise<void> {
  if (!existsSync(modelDir)) {
    console.log("no advisory models — run scripts/export-domain-model.py first (needs the lists)");
    return;
  }

  await mkdir(chromeDir, { recursive: true });
  await mkdir(androidDir, { recursive: true });
  let copied = 0;
  for (const category of ADVISORY_CATEGORIES) {
    const src = join(modelDir, `${category}.json`);
    if (!existsSync(src)) {
      console.log(`${category}: no model, skipping`);
      continue;
    }
    for (const dir of [chromeDir, androidDir]) {
      await copyFile(src, join(dir, `${category}.json`));
    }
    const { size } = await stat(join(chromeDir, `${category}.json`));
    console.log(`${category}: ${Math.round(size / 1024)} KB -> chrome, android`);
    copied++;
  }

  if (copied === 0) {
    console.log("no models copied — the advisory tier will be inert");
    return;
  }

  // A stale model for a category that has since been withdrawn would keep being
  // served, and the extension would go on warning from weights nobody meant to
  // ship. Named categories are the whole set, so anything else here is stale.
  const allowed = new Set(ADVISORY_CATEGORIES.map((c) => `${c}.json`));
  for (const dir of [chromeDir, androidDir]) {
    for (const name of await readdir(dir)) {
      if (!allowed.has(name)) {
        throw new Error(`${join(dir, name)} is not a shipped advisory model — delete it`);
      }
    }
  }
}

build().catch((e) => {
  console.error(e);
  process.exit(1);
});
