import { getCategory, type CategoryId } from "./categories.js";
import type { DNRRule } from "./types.js";

/** Max domains packed into a single declarativeNetRequest rule's requestDomains. */
export const DOMAINS_PER_RULE = 1000;

export interface GenerateOptions {
  /**
   * Where main-frame blocks redirect to. If set, main-frame requests use a
   * redirect action to this extension-relative path (host/category appended as
   * query params by the caller-provided template). If omitted, plain block.
   */
  blockedPagePath?: string;
}

/**
 * Generate declarativeNetRequest rules for a category from a list of bare domains.
 *
 * Domains are packed into `requestDomains` (many per rule) to stay far below MV3
 * rule-count limits. Rule ids are namespaced by the category's `ruleIdBase`.
 *
 * When `blockedPagePath` is given, a main-frame redirect rule and a lower-priority
 * block rule (for sub-resources) are emitted per chunk; otherwise a single block
 * rule per chunk covers all resource types.
 */
export function generateRulesForCategory(
  category: CategoryId,
  domains: string[],
  opts: GenerateOptions = {},
): DNRRule[] {
  const base = getCategory(category).ruleIdBase;
  const unique = [...new Set(domains.filter(Boolean))].sort();
  const rules: DNRRule[] = [];
  let idOffset = 0;

  for (let i = 0; i < unique.length; i += DOMAINS_PER_RULE) {
    const chunk = unique.slice(i, i + DOMAINS_PER_RULE);

    if (opts.blockedPagePath) {
      rules.push({
        id: base + idOffset++,
        priority: 1,
        action: {
          type: "redirect",
          redirect: { extensionPath: `${opts.blockedPagePath}?category=${category}` },
        },
        condition: { requestDomains: chunk, resourceTypes: ["main_frame"] },
      });
      rules.push({
        id: base + idOffset++,
        priority: 1,
        action: { type: "block" },
        condition: {
          requestDomains: chunk,
          resourceTypes: ["sub_frame", "script", "image", "stylesheet", "xmlhttprequest", "media", "font", "object", "ping", "websocket", "other"],
        },
      });
    } else {
      rules.push({
        id: base + idOffset++,
        priority: 1,
        action: { type: "block" },
        condition: { requestDomains: chunk },
      });
    }
  }

  return rules;
}
