/**
 * Re-exported from `packages/core` so the generator and the Chrome extension
 * share one implementation — two copies of a scrambler that must agree byte for
 * byte is exactly the kind of drift that silently stops the apps matching
 * anything.
 *
 * See `packages/core/src/scramble.ts` for what this is and isn't worth.
 */
export {
  SCRAMBLE_FORMAT,
  HASHED_FORMAT,
  scrambleDomain,
  unscrambleDomain,
  plainDomains,
} from "../packages/core/src/scramble.js";
