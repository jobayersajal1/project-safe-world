import type { CategoryId } from "./categories.js";

export interface Settings {
  /** Master switch. When false, nothing is blocked. */
  enabled: boolean;
  /** Per-category enable flags. */
  categories: Record<CategoryId, boolean>;
  /** Domains the user always allows, even if a category would block them. */
  customAllow: string[];
  /** Domains the user always blocks, regardless of category. */
  customBlock: string[];
  /** URL to fetch remote list updates from. Empty string disables remote updates. */
  remoteUpdateUrl: string;
  /** How often (in hours) to fetch remote updates. */
  remoteUpdateIntervalHours: number;
  /** Timestamp (ms) of the last successful remote update, or 0 if never. */
  lastRemoteUpdate: number;
}

export interface DailyStats {
  /** Local date key, e.g. "2026-07-26". */
  date: string;
  /** Number of navigations blocked on that date. */
  blocked: number;
}

/** A minimal declarativeNetRequest rule shape (subset we generate). */
export interface DNRRule {
  id: number;
  priority: number;
  action:
    | { type: "block" }
    | { type: "allow" }
    | { type: "redirect"; redirect: { extensionPath?: string; url?: string } };
  condition: {
    requestDomains?: string[];
    urlFilter?: string;
    resourceTypes?: string[];
    isUrlFilterCaseSensitive?: boolean;
  };
}

/** Shape of a remote update payload fetched from `remoteUpdateUrl`. */
export interface RemoteUpdatePayload {
  version: number;
  /**
   * Identifies this exact set of domains — a content hash, written by
   * `build-remote-payload.ts`.
   *
   * Clients store the last one they applied and skip the work when it repeats,
   * so a daily fetch of an unchanged list costs a download and nothing else.
   * Optional: a payload published before this field still applies, it just
   * re-applies every time.
   */
  updateId?: string;
  /**
   * How the entries in `domains` are encoded — see `scramble.ts`. Absent means
   * plaintext, so payloads published before this field keep working.
   *
   * The platforms publish different formats: Android fetches one-way digests it
   * matches directly, while iOS and Chrome fetch scrambled domains they reverse
   * at runtime, because their rule files need literal names.
   */
  format?: string;
  /** Additional domains to block, grouped by category. */
  domains: Partial<Record<CategoryId, string[]>>;
}
