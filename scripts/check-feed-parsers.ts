/**
 * Parse the live subscription feeds and compare the result against the entry
 * count each one declares in its own header.
 *
 * Unit tests pin a couple of dozen vectors; they cannot tell you that a parser
 * silently drops 5% of a 450,000-line file, or that a publisher changed syntax
 * last Tuesday. This does, and it is the only check that looks at what the
 * upstreams are actually serving right now.
 *
 *   npx tsx scripts/check-feed-parsers.ts
 *
 * Downloads to a temp directory and never writes a feed into the repo — these
 * are the non-redistributable lists, and the whole point of the subscription
 * design is that we never hold a copy. Nothing here is committed; see
 * `SUBSCRIPTIONS` in packages/core/src/subscriptions.ts for why.
 */
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { createHash } from "node:crypto";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { parseFeed, parseFeedLine } from "../packages/core/src/feed.js";
import { SUBSCRIPTIONS } from "../packages/core/src/subscriptions.js";

/**
 * SHA-256 of the sorted domains, so the Kotlin port can be checked against this
 * over the real feeds rather than only against the shared vectors.
 *
 * Sorted, so a difference in iteration order is not reported as a behavioural
 * difference. `FeedParserCorpusTest` prints the same digest — see its comment for
 * how to run the comparison.
 */
function corpusDigest(domains: readonly string[]): string {
  return createHash("sha256").update([...domains].sort().join("\n")).digest("hex");
}

/** Where each publisher states its own entry count, so we can check ours. */
const DECLARED_COUNT: Record<string, RegExp> = {
  adblock: /Number of entries:\s*(\d+)/i,
  domainswild: /Entries:\s*(\d+)/i,
  domains: /Entries:\s*(\d+)/i,
  hosts: /Number of entries:\s*(\d+)/i,
};

/** Below this share of the declared count, assume the format changed. */
const MIN_RATIO = 0.95;

async function main(): Promise<void> {
  const cache = process.env.FEED_CACHE ?? mkdtempSync(join(tmpdir(), "safeworld-feeds-"));
  console.log(`cache: ${cache}\n`);

  let failed = false;
  let total = 0;

  for (const sub of SUBSCRIPTIONS) {
    const path = join(cache, `${sub.id}.txt`);
    if (!existsSync(path)) {
      const res = await fetch(sub.url, { redirect: "follow" });
      if (!res.ok) {
        console.log(`${sub.id.padEnd(17)} FETCH FAILED HTTP ${res.status}`);
        failed = true;
        continue;
      }
      writeFileSync(path, await res.text());
    }

    const text = readFileSync(path, "utf8");
    const declared = Number(DECLARED_COUNT[sub.format]?.exec(text)?.[1] ?? 0);

    const started = Date.now();
    const { domains, skipped } = parseFeed(text, sub.format);
    const ms = Date.now() - started;
    total += domains.length;

    const ratio = declared ? domains.length / declared : 1;
    const verdict = ratio < MIN_RATIO ? "  <-- CHECK" : "";
    if (ratio < MIN_RATIO) failed = true;

    console.log(
      `${sub.id.padEnd(17)} declared=${String(declared).padStart(7)}` +
        ` parsed=${String(domains.length).padStart(7)}` +
        ` (${(ratio * 100).toFixed(2)}%)` +
        ` skipped=${String(skipped).padStart(5)} ${String(ms).padStart(5)}ms${verdict}`
    );
    console.log(`${" ".repeat(18)}sha256(sorted) ${corpusDigest(domains)}`);

    if (skipped > 0) {
      // "skipped" as a bare number invites a shrug. Show what was dropped so
      // the reader can judge whether it should have been.
      const dropped: string[] = [];
      for (const line of text.split("\n")) {
        const t = line.trim();
        if (!t || t.startsWith("#") || t.startsWith("!") || t.startsWith("[")) continue;
        if (parseFeedLine(line, sub.format) === null) dropped.push(t);
        if (dropped.length >= 5) break;
      }
      console.log(`${" ".repeat(18)}dropped e.g. ${JSON.stringify(dropped)}`);
    }
  }

  console.log(
    `\ntotal ${total.toLocaleString()} domains` +
      ` = ${((total * 8) / 1048576).toFixed(1)} MB as 8-byte digests`
  );

  if (failed) {
    console.error("\nA feed parsed well below its declared count, or failed to fetch.");
    process.exit(1);
  }
}

await main();
