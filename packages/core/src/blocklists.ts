import type { CategoryId } from "./categories.js";
import list1 from "./blocklists/list1.json" with { type: "json" };
import list2 from "./blocklists/list2.json" with { type: "json" };
import list3 from "./blocklists/list3.json" with { type: "json" };
import list4 from "./blocklists/list4.json" with { type: "json" };
import list5 from "./blocklists/list5.json" with { type: "json" };

interface BlocklistFile {
  category: string;
  source: string;
  domains: string[];
}

// Keyed by the opaque ids — see the note on CategoryId in categories.ts for
// why the file names don't say what they contain.
const files: Record<CategoryId, BlocklistFile> = {
  list1: list1 as BlocklistFile,
  list2: list2 as BlocklistFile,
  list3: list3 as BlocklistFile,
  list4: list4 as BlocklistFile,
  list5: list5 as BlocklistFile,
};

/** Bundled domains for a category (as authored, not normalized). */
export function bundledDomains(category: CategoryId): string[] {
  return files[category].domains;
}

/** Bundled domains for every category as Sets, for use with `decide`. */
export function bundledBlocklists(): Record<CategoryId, Set<string>> {
  return {
    list1: new Set(files.list1.domains),
    list2: new Set(files.list2.domains),
    list3: new Set(files.list3.domains),
    list4: new Set(files.list4.domains),
    list5: new Set(files.list5.domains),
  };
}
