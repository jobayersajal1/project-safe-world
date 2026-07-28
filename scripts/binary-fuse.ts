/**
 * Binary fuse16 filter construction.
 *
 * A blocklist only ever needs one question answered — "is this host on the
 * list?" — and never needs the list back. That lets us store an approximate
 * membership structure instead of the domains, which is the difference between
 * shipping 4.5M entries in ~10 MB and shipping them in ~86 MB of hashes (or
 * ~225 MB of strings in memory).
 *
 * A binary fuse filter beats a Bloom filter on both counts: ~18.2 bits per entry
 * here against Bloom's ~24 for a comparable error rate, and three memory reads
 * per lookup instead of k.
 *
 * **The trade is one-directional, which is why it is acceptable here.** A false
 * positive blocks a site that isn't on the list (~1 in 65,536). A false negative
 * — letting a blocked site through — is *impossible*: every domain that went in
 * is always found. Erring toward over-blocking is the right direction for this
 * app, and the UI already has "allow once"/"always allow" to undo it. In a
 * system with no override this trade would not be worth making.
 *
 * Reference: Graf & Lemire, "Binary Fuse Filters: Fast and Smaller Than Xor
 * Filters" (2022). The Kotlin reader is `FuseFilter.kt`; the two must agree
 * exactly, so `FuseFilterTest` pins shared vectors.
 */

/** Bytes in the header: magic(4) + version(1) + segmentLengthLog(1) + reserved(2) + seed(8) + size(4) + segmentCount(4) + arrayLength(4) */
export const HEADER_BYTES = 28;

/** `SWF1` — so a truncated or wrong file fails loudly instead of matching nothing. */
export const MAGIC = 0x53574631;

export const FORMAT_VERSION = 1;

/** 64-bit mix (splitmix64 finalizer), used for both hashing and seed advance. */
function mix64(z: bigint): bigint {
  const M = 0xffffffffffffffffn;
  z = (z + 0x9e3779b97f4a7c15n) & M;
  let x = z;
  x = ((x ^ (x >> 30n)) * 0xbf58476d1ce4e5b9n) & M;
  x = ((x ^ (x >> 27n)) * 0x94d049bb133111ebn) & M;
  return (x ^ (x >> 31n)) & M;
}

/** Multiply-shift reduction: maps a 64-bit value into [0, n) without division. */
function reduce(hash: number, n: number): number {
  return Math.floor((hash * n) / 4294967296);
}

interface Hashes {
  h0: number;
  h1: number;
  h2: number;
}

export class BinaryFuse16 {
  readonly seed: bigint;
  readonly segmentLength: number;
  readonly segmentLengthLog: number;
  readonly segmentCount: number;
  readonly segmentCountLength: number;
  readonly arrayLength: number;
  readonly size: number;
  readonly fingerprints: Uint16Array;

  private constructor(init: {
    seed: bigint;
    segmentLength: number;
    segmentLengthLog: number;
    segmentCount: number;
    segmentCountLength: number;
    arrayLength: number;
    size: number;
    fingerprints: Uint16Array;
  }) {
    Object.assign(this, init);
  }

  private static hashOf(key: bigint, seed: bigint): bigint {
    return mix64(key + seed);
  }

  private static fingerprintOf(hash: bigint): number {
    return Number((hash ^ (hash >> 32n)) & 0xffffn);
  }

  private static locations(
    hash: bigint,
    segmentLength: number,
    segmentLengthMask: number,
    segmentCountLength: number,
  ): Hashes {
    const hi = Number((hash * BigInt(segmentCountLength)) >> 64n) >>> 0;
    const h0 = hi >>> 0;
    const h1 = (h0 + segmentLength) >>> 0;
    const h2 = (h1 + segmentLength) >>> 0;
    const r1 = Number((hash >> 18n) & 0xffffffffn) >>> 0;
    const r2 = Number(hash & 0xffffffffn) >>> 0;
    return {
      h0: h0 ^ 0,
      h1: (h1 ^ (r1 & segmentLengthMask)) >>> 0,
      h2: (h2 ^ (r2 & segmentLengthMask)) >>> 0,
    };
  }

  /**
   * Build a filter over `keys` (64-bit, already deduplicated).
   *
   * Construction is randomised and can fail; it retries with a new seed. The
   * loop is bounded because a persistent failure means a bug rather than bad
   * luck, and silently returning a filter that matches nothing is the failure
   * mode this project cares most about avoiding.
   */
  static build(keys: bigint[]): BinaryFuse16 {
    const size = keys.length;
    if (size === 0) throw new Error("cannot build a filter over zero keys");

    const arity = 3;
    let segmentLength = size === 0 ? 4 : 1 << Math.floor(Math.log(size) / Math.log(3.33) + 2.25);
    if (segmentLength > 262144) segmentLength = 262144;
    const segmentLengthLog = Math.round(Math.log2(segmentLength));
    segmentLength = 1 << segmentLengthLog;
    const segmentLengthMask = segmentLength - 1;

    const sizeFactor = size <= 1 ? 0 : Math.max(1.125, 0.875 + 0.25 * (Math.log(1000000) / Math.log(size)));
    const capacity = size <= 1 ? 0 : Math.round(size * sizeFactor);
    let initSegmentCount = Math.floor((capacity + segmentLength - 1) / segmentLength) - (arity - 1);
    let arrayLength = (initSegmentCount + arity - 1) * segmentLength;
    let segmentCount = Math.floor((arrayLength + segmentLength - 1) / segmentLength) - (arity - 1);
    if (segmentCount <= arity - 1) segmentCount = 1;
    arrayLength = (segmentCount + arity - 1) * segmentLength;
    const segmentCountLength = segmentCount * segmentLength;

    const fingerprints = new Uint16Array(arrayLength);
    const alone = new Uint32Array(arrayLength);
    const t2count = new Uint8Array(arrayLength);
    const reverseH = new Uint8Array(size);
    const t2hash = new BigUint64Array(arrayLength);
    const reverseOrder = new BigUint64Array(size + 1);

    let seed = 0x726b2b9d438b9d4dn;

    for (let attempt = 0; attempt < 100; attempt++) {
      t2count.fill(0);
      t2hash.fill(0n);
      reverseOrder.fill(0n);
      // Sentinel. The bucketing loop below treats a zero slot as free, so
      // without a non-zero guard at [size] the last key can spill one past the
      // end into a slot the assignment loop never reads — losing exactly one
      // domain per filter, silently, as a false negative.
      reverseOrder[size] = 1n;
      let blockBits = 1;
      while (1 << blockBits < segmentCount) blockBits++;

      const startPos = new Uint32Array(1 << blockBits);
      for (let i = 0; i < startPos.length; i++) {
        startPos[i] = Math.floor((i * size) / startPos.length);
      }

      // Bucket the keys by their first segment so construction is cache-friendly.
      const M = 0xffffffffffffffffn;
      for (const key of keys) {
        const hash = BinaryFuse16.hashOf(key, seed);
        let segIndex = Number((hash >> BigInt(64 - blockBits)) & ((1n << BigInt(blockBits)) - 1n));
        while (reverseOrder[startPos[segIndex]!] !== 0n) {
          segIndex = (segIndex + 1) & ((1 << blockBits) - 1);
        }
        reverseOrder[startPos[segIndex]!] = hash & M;
        startPos[segIndex]!++;
      }

      let error = false;
      for (let i = 0; i < size; i++) {
        const hash = reverseOrder[i]!;
        const { h0, h1, h2 } = BinaryFuse16.locations(
          hash,
          segmentLength,
          segmentLengthMask,
          segmentCountLength,
        );
        const hs = [h0, h1, h2];
        for (let j = 0; j < 3; j++) {
          const h = hs[j]!;
          if (h >= arrayLength) {
            error = true;
            break;
          }
          t2count[h]! += 4;
          // Low 2 bits track which location this is, so peeling knows which
          // slot a key was assigned to. Omitting it silently breaks the peel.
          t2count[h]! ^= j;
          t2hash[h]! ^= hash;
        }
        if (error) break;
      }
      if (error) {
        seed = mix64(seed);
        continue;
      }

      // Peel: repeatedly remove keys that are alone in some slot.
      let qSize = 0;
      for (let i = 0; i < arrayLength; i++) {
        alone[qSize] = i;
        qSize += (t2count[i]! >> 2) === 1 ? 1 : 0;
      }

      let stackSize = 0;
      while (qSize > 0) {
        qSize--;
        const index = alone[qSize]!;
        if ((t2count[index]! >> 2) === 1) {
          const hash = t2hash[index]!;
          const found = t2count[index]! & 3;
          reverseOrder[stackSize] = hash;
          reverseH[stackSize] = found;
          stackSize++;

          const { h0, h1, h2 } = BinaryFuse16.locations(
            hash,
            segmentLength,
            segmentLengthMask,
            segmentCountLength,
          );
          const hs = [h0, h1, h2];
          for (let j = 0; j < 3; j++) {
            if (j === found) continue;
            const h = hs[j]!;
            t2count[h]! -= 4;
            t2count[h]! ^= j;
            t2hash[h]! ^= hash;
            if ((t2count[h]! >> 2) === 1) {
              alone[qSize] = h;
              qSize++;
            }
          }
        }
      }

      if (stackSize === size) {
        // Assign fingerprints in reverse peel order.
        fingerprints.fill(0);
        for (let i = stackSize - 1; i >= 0; i--) {
          const hash = reverseOrder[i]!;
          const found = reverseH[i]!;
          const { h0, h1, h2 } = BinaryFuse16.locations(
            hash,
            segmentLength,
            segmentLengthMask,
            segmentCountLength,
          );
          const hs = [h0, h1, h2];
          let fp = BinaryFuse16.fingerprintOf(hash);
          for (let j = 0; j < 3; j++) {
            if (j === found) continue;
            fp ^= fingerprints[hs[j]!]!;
          }
          fingerprints[hs[found]!] = fp & 0xffff;
        }

        return new BinaryFuse16({
          seed,
          segmentLength,
          segmentLengthLog,
          segmentCount,
          segmentCountLength,
          arrayLength,
          size,
          fingerprints,
        });
      }

      seed = mix64(seed);
    }

    throw new Error(
      `binary fuse construction failed for ${size} keys after 100 attempts — this is a bug, not bad luck`,
    );
  }

  /** True if `key` was in the built set (or is a rare false positive). */
  contains(key: bigint): boolean {
    const hash = BinaryFuse16.hashOf(key, this.seed);
    let fp = BinaryFuse16.fingerprintOf(hash);
    const { h0, h1, h2 } = BinaryFuse16.locations(
      hash,
      this.segmentLength,
      this.segmentLength - 1,
      this.segmentCountLength,
    );
    fp ^= this.fingerprints[h0]! ^ this.fingerprints[h1]! ^ this.fingerprints[h2]!;
    return (fp & 0xffff) === 0;
  }

  /** Serialize: big-endian header, then fingerprints big-endian. */
  serialize(): Uint8Array {
    const out = new Uint8Array(HEADER_BYTES + this.fingerprints.length * 2);
    const view = new DataView(out.buffer);
    view.setUint32(0, MAGIC, false);
    view.setUint8(4, FORMAT_VERSION);
    view.setUint8(5, this.segmentLengthLog);
    view.setUint16(6, 0, false);
    view.setBigUint64(8, this.seed, false);
    view.setUint32(16, this.size, false);
    view.setUint32(20, this.segmentCount, false);
    view.setUint32(24, this.arrayLength, false);
    for (let i = 0; i < this.fingerprints.length; i++) {
      view.setUint16(HEADER_BYTES + i * 2, this.fingerprints[i]!, false);
    }
    return out;
  }
}

/** First 8 bytes of a hex digest as a big-endian 64-bit key. */
export function keyFromHexDigest(hex: string): bigint {
  return BigInt("0x" + hex.slice(0, 16));
}
