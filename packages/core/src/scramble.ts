import type { CategoryId } from "./categories.js";
import type { RemoteUpdatePayload } from "./types.js";

/**
 * Reversible scrambling for the domains published to the public lists repo.
 *
 * **Obfuscation, not encryption.** The key ships inside every app, so anyone
 * who unpacks one can reverse it, and the unscrambled domains land in the
 * generated rule files on the device regardless. What it buys: the file at the
 * public URL isn't a readable list of gambling and adult sites, so it can't be
 * skimmed, indexed, or stumbled on as one. Don't rely on it for anything else.
 *
 * Used by the platforms that need the real names back — iOS and Chrome, whose
 * rule files must contain literal domains. Android instead publishes one-way
 * digests, which it can match without ever reversing them.
 *
 * XOR with a repeating key then base64: chosen because the same few lines port
 * to TypeScript, Swift, and Kotlin with no dependencies and no platform crypto
 * differences to reconcile.
 */

/** Payload `format` for scrambled domains. Bump if the scheme ever changes. */
export const SCRAMBLE_FORMAT = "xor-b64-v1";

/** Payload `format` for Android's one-way digests, which are not domains. */
export const HASHED_FORMAT = "sha256-128-hex";

const KEY = "safe-world-list-v1";

function xorBytes(bytes: Uint8Array): Uint8Array {
  const key = new TextEncoder().encode(KEY);
  const out = new Uint8Array(bytes.length);
  for (let i = 0; i < bytes.length; i++) out[i] = bytes[i]! ^ key[i % key.length]!;
  return out;
}

export function scrambleDomain(domain: string): string {
  const trimmed = domain.trim().toLowerCase();
  if (trimmed === "") return "";
  const scrambled = xorBytes(new TextEncoder().encode(trimmed));
  let binary = "";
  for (const b of scrambled) binary += String.fromCharCode(b);
  return btoa(binary);
}

export function unscrambleDomain(scrambled: string): string {
  if (scrambled === "") return "";
  const binary = atob(scrambled);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return new TextDecoder().decode(xorBytes(bytes));
}

/**
 * The payload's domains as plaintext, unscrambling when the payload says it is
 * scrambled.
 *
 * Throws rather than returning an empty result for a format this platform can't
 * use, so the failure surfaces in the UI. Silently yielding no domains would
 * leave the app looking healthy while blocking nothing new — the worst way for
 * this to break.
 */
export function plainDomains(
  payload: RemoteUpdatePayload,
): Partial<Record<CategoryId, string[]>> {
  const format = payload.format;

  if (format === HASHED_FORMAT) {
    throw new Error(
      "This list is hashed for Android and can't be used here — " +
        "point this platform at the scrambled list instead.",
    );
  }
  if (format !== undefined && format !== SCRAMBLE_FORMAT) {
    throw new Error(`Unrecognized list format "${format}".`);
  }

  const out: Partial<Record<CategoryId, string[]>> = {};
  for (const [key, value] of Object.entries(payload.domains ?? {})) {
    const list = value ?? [];
    // An older payload with no `format` is plaintext, so it keeps working.
    out[key as CategoryId] =
      format === SCRAMBLE_FORMAT ? list.map(unscrambleDomain).filter(Boolean) : list;
  }
  return out;
}
