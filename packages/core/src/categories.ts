/**
 * Category identifiers are deliberately opaque.
 *
 * They are the keys in the published payload, the generated file names, and the
 * DNR ruleset ids — all of which are visible in the lists repo and in an
 * unpacked app. `"adult"` would announce what a list is even though its domains
 * are encoded, which defeats the point of encoding them. `label` carries the
 * human meaning and is what the UI shows; nothing user-facing says "list2".
 *
 * The mapping is not a secret (it's right here), it just isn't advertised by
 * the artifacts themselves.
 */
export type CategoryId = "list1" | "list2" | "list3" | "list4" | "list5" | "list6" | "list7";

export interface CategoryMeta {
  id: CategoryId;
  /** Short human label for UI. */
  label: string;
  /** One-line description shown in options/popup. */
  description: string;
  /** Whether the category is enabled by default on first install. */
  defaultEnabled: boolean;
  /**
   * Whether the user may turn this category off.
   *
   * The three protection categories are the reason the app exists — installing
   * it is the decision to block them — so platforms that enforce this (Android,
   * which forces them on) treat them as non-optional. The opt-in categories are
   * lifestyle choices rather than protection, off until the user asks for them.
   *
   * Chrome is the exception by design: it's the self-control build with no PIN,
   * where every category toggles freely. The flag is still meaningful there as
   * the answer to "is this one part of the baseline promise or an extra?".
   */
  optional: boolean;
  /**
   * Stable numeric id used to namespace declarativeNetRequest rule ids so each
   * category's generated rules occupy a disjoint integer range.
   * Range for category N is [N*RULE_ID_STRIDE, (N+1)*RULE_ID_STRIDE).
   */
  ruleIdBase: number;
}

/** Each category gets this many rule ids reserved. Keep generated rule counts below it. */
export const RULE_ID_STRIDE = 1_000_000;

export const CATEGORIES: readonly CategoryMeta[] = [
  {
    id: "list1",
    label: "Scam & Malware",
    description: "Phishing, fraud, and malware-hosting sites.",
    defaultEnabled: true,
    optional: false,
    ruleIdBase: 1 * RULE_ID_STRIDE,
  },
  {
    id: "list2",
    label: "Gambling",
    description: "Casinos, betting, and gambling sites.",
    defaultEnabled: true,
    optional: false,
    ruleIdBase: 2 * RULE_ID_STRIDE,
  },
  {
    id: "list3",
    label: "Adult Content",
    description: "Pornography and explicit adult sites.",
    defaultEnabled: true,
    optional: false,
    ruleIdBase: 3 * RULE_ID_STRIDE,
  },
  {
    id: "list4",
    label: "Social Media",
    description: "Facebook, Instagram, X/Twitter, TikTok, and similar feeds.",
    defaultEnabled: false,
    optional: true,
    ruleIdBase: 4 * RULE_ID_STRIDE,
  },
  {
    id: "list5",
    label: "Entertainment",
    description: "YouTube, Netflix, and other streaming and video sites.",
    defaultEnabled: false,
    optional: true,
    ruleIdBase: 5 * RULE_ID_STRIDE,
  },
  {
    id: "list7",
    label: "Drugs & Illegal",
    description: "Drug marketplaces and paraphernalia, plus piracy and warez sites.",
    defaultEnabled: true,
    // Mandatory, like scam/gambling/adult: these are the harms the app exists
    // to block, not lifestyle choices someone opts into. It carries no UI row
    // for the same reason those three do not — it is stated in the count.
    optional: false,
    ruleIdBase: 7 * RULE_ID_STRIDE,
  },
  {
    id: "list6",
    label: "Games",
    description: "Game portals, browser games, and gaming platforms.",
    defaultEnabled: false,
    optional: true,
    ruleIdBase: 6 * RULE_ID_STRIDE,
  },
] as const;

/**
 * Categories the user opts into. Split out because the two groups are presented
 * very differently: the protection ones are stated as a promise the app already
 * keeps, these are offered as a question ("want to block more?").
 */
export const OPTIONAL_CATEGORIES: readonly CategoryMeta[] = CATEGORIES.filter((c) => c.optional);

export const CATEGORY_IDS: readonly CategoryId[] = CATEGORIES.map((c) => c.id);

export function getCategory(id: CategoryId): CategoryMeta {
  const meta = CATEGORIES.find((c) => c.id === id);
  if (!meta) throw new Error(`Unknown category: ${id}`);
  return meta;
}

/** The declarativeNetRequest static ruleset id for a category (matches manifest). */
export function rulesetIdFor(id: CategoryId): string {
  return `rules_${id}`;
}
