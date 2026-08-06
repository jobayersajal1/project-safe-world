"""The feature map for the advisory domain model — the spec every port copies.

This file is the definition. `DomainModel.kt`, `DomainModel.swift`,
`DomainModel.cs` and `packages/core/src/advisory.ts` reimplement `features()`
by hand and pin the same vectors in their tests, the way `Scramble` and
`DomainHasher` already do. Four ports disagreeing about the same domain is the
failure that discipline exists to prevent, so **nothing here may change without
changing all of them.**

Everything is chosen to be portable rather than clever:

- **FNV-1a, 32-bit.** Four lines in every language, no dependencies, no platform
  hash that differs by runtime. A stronger hash buys nothing: collisions in a
  2^18 table are already the dominant error and are what the model is trained
  against.
- **Character n-grams of length 3, 4 and 5, over the whole host including its
  dots**, wrapped in `^`/`$`. The dots and the boundary markers are what let the
  model learn that `bet` at the start of a label is different from `bet` in the
  middle of one — `bet365` versus `alphabet`.
- **The signed hashing trick.** Bit 31 of the same hash picks the sign. Two
  n-grams colliding in the table then cancel as often as they reinforce, so a
  collision costs noise instead of a systematic bias toward "blocked".
- **L2 normalisation.** Domain lengths vary by an order of magnitude and an
  unnormalised count vector makes a long name score high for being long. That is
  precisely the false positive this model must not make.

`vectorize()` is a fast bulk path for training and is required to agree with
`features()` exactly; `test_domain_features.py` checks that on real hosts.
"""

from __future__ import annotations

import numpy as np

HASH_BITS = 18
TABLE_SIZE = 1 << HASH_BITS
TABLE_MASK = TABLE_SIZE - 1
NGRAMS = (3, 4, 5)

FNV_OFFSET = 0x811C9DC5
FNV_PRIME = 0x01000193

_SEP = 0x00


def normalize_host(host: str) -> str:
    """What every port must do before featurising.

    `normalizeHost` in `packages/core/src/matcher.ts` is the existing shared
    rule; this adds stripping a leading `www.` because the model should not
    learn that prefix as a signal — it appears on blocked and allowed names
    alike, and the hosts sinkhole already emits both forms.
    """
    host = host.strip().lower().rstrip(".")
    if host.startswith("www."):
        host = host[4:]
    return host


def _fnv(data: bytes) -> int:
    h = FNV_OFFSET
    for b in data:
        h = ((h ^ b) * FNV_PRIME) & 0xFFFFFFFF
    return h


def _bucket(value: int, edges: tuple[int, ...]) -> int:
    for i, e in enumerate(edges):
        if value <= e:
            return i
    return len(edges)


def structural_tokens(host: str) -> list[str]:
    """Whole-name properties an n-gram window cannot express.

    A 5-gram sees `.xyz` only as part of whatever precedes it, so the model can
    only learn a cheap TLD in combination with specific neighbouring characters
    — thousands of separate weights for one fact. The same goes for "this name
    is 40 characters of digits and hyphens across two labels", which is most of
    what distinguishes list1's throwaway domains from ordinary ones.

    These are hashed into the same table as the n-grams, so they cost nothing in
    format, size, or porting effort: they are just more strings to hash.
    """
    labels = host.split(".")
    digits = sum(c.isdigit() for c in host)
    hyphens = host.count("-")
    return [
        "\x01tld=" + labels[-1] if len(labels) > 1 else "\x01tld=",
        "\x01sld=" + (labels[-2] if len(labels) > 1 else ""),
        "\x01labels=%d" % min(len(labels), 6),
        "\x01len=%d" % _bucket(len(host), (8, 12, 16, 20, 26, 34, 48)),
        "\x01digits=%d" % _bucket(int(100 * digits / max(1, len(host))), (0, 5, 15, 30, 50)),
        "\x01hyphens=%d" % _bucket(hyphens, (0, 1, 2, 4)),
        "\x01longest=%d" % _bucket(max(len(l) for l in labels), (4, 7, 10, 14, 20, 30)),
    ]


def features(host: str) -> dict[int, float]:
    """Reference implementation: one host to its L2-normalised sparse vector."""
    clean = normalize_host(host)
    text = ("^" + clean + "$").encode("utf8")
    acc: dict[int, float] = {}
    for n in NGRAMS:
        for i in range(len(text) - n + 1):
            h = _fnv(text[i : i + n])
            idx = h & TABLE_MASK
            sign = -1.0 if (h >> 31) & 1 else 1.0
            acc[idx] = acc.get(idx, 0.0) + sign
    if clean:
        for token in structural_tokens(clean):
            h = _fnv(token.encode("utf8"))
            idx = h & TABLE_MASK
            sign = -1.0 if (h >> 31) & 1 else 1.0
            acc[idx] = acc.get(idx, 0.0) + sign
    norm = np.sqrt(sum(v * v for v in acc.values()))
    if norm == 0.0:
        return {}
    return {k: v / norm for k, v in acc.items()}


def vectorize(hosts: list[str]):
    """Bulk path: many hosts to one CSR matrix, with the same values.

    The whole batch is laid out as one byte array separated by NUL, and each
    n-gram length is hashed at every start position at once — `n` numpy
    operations over the batch rather than a Python loop per n-gram. Positions
    that straddle a separator are dropped afterwards, which is cheaper than
    avoiding them.
    """
    from scipy.sparse import csr_matrix

    encoded = [("^" + normalize_host(h) + "$").encode("utf8") for h in hosts]
    lengths = np.fromiter((len(e) for e in encoded), dtype=np.int64, count=len(encoded))
    joined = bytes([_SEP]).join(encoded)
    arr = np.frombuffer(joined, dtype=np.uint8)

    # Start byte of each host inside `arr`; every host is followed by one
    # separator, so each start is the previous end plus one.
    starts = np.zeros(len(encoded), dtype=np.int64)
    if len(encoded) > 1:
        starts[1:] = np.cumsum(lengths[:-1] + 1)

    rows_all, cols_all, vals_all = [], [], []
    for n in NGRAMS:
        count = arr.size - n + 1
        if count <= 0:
            continue
        h = np.full(count, FNV_OFFSET, dtype=np.uint32)
        ok = np.ones(count, dtype=bool)
        with np.errstate(over="ignore"):
            for k in range(n):
                window = arr[k : k + count]
                ok &= window != _SEP
                h = (h ^ window) * np.uint32(FNV_PRIME)
        pos = np.nonzero(ok)[0]
        if pos.size == 0:
            continue
        hv = h[pos]
        rows_all.append(np.searchsorted(starts, pos, side="right") - 1)
        cols_all.append((hv & np.uint32(TABLE_MASK)).astype(np.int64))
        vals_all.append(np.where((hv >> np.uint32(31)) & np.uint32(1), -1.0, 1.0))

    # Structural tokens: a handful per host, so a plain loop is cheaper than
    # another vectorised pass and keeps this identical to `features()`.
    s_rows, s_cols, s_vals = [], [], []
    for row, host in enumerate(hosts):
        clean = normalize_host(host)
        if not clean:
            continue
        for token in structural_tokens(clean):
            h = _fnv(token.encode("utf8"))
            s_rows.append(row)
            s_cols.append(h & TABLE_MASK)
            s_vals.append(-1.0 if (h >> 31) & 1 else 1.0)
    if s_rows:
        rows_all.append(np.array(s_rows, dtype=np.int64))
        cols_all.append(np.array(s_cols, dtype=np.int64))
        vals_all.append(np.array(s_vals, dtype=np.float64))

    if not rows_all:
        # Every host in the batch featurised to nothing — an empty string, or a
        # name too short for a 3-gram. An all-zero matrix scores to the bias,
        # which is what `features()` implies too.
        return csr_matrix((len(hosts), TABLE_SIZE), dtype=np.float64)

    rows = np.concatenate(rows_all)
    cols = np.concatenate(cols_all)
    vals = np.concatenate(vals_all)

    m = csr_matrix((vals, (rows, cols)), shape=(len(hosts), TABLE_SIZE))
    m.sum_duplicates()
    norms = np.sqrt(m.multiply(m).sum(axis=1)).A.ravel()
    norms[norms == 0.0] = 1.0
    inv = 1.0 / norms
    m = m.multiply(inv[:, None]).tocsr()
    return m
