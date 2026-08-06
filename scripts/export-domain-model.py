#!/usr/bin/env python3
"""Export the trained advisory models into the form `advisory.ts` loads.

    python3 scripts/export-domain-model.py --work /tmp/sw-model

Only the categories that cleared the gate are exported. The rest carry no model
and stay list-only, which is the point of enabling per category rather than
all-or-nothing.

**Both thresholds are measured, not chosen.** Each category names two ranks in
the held-out negatives and the thresholds are the scores at those ranks. Writing
a round number like 0.5 would be inventing an operating point nobody measured,
and the whole argument for shipping this rests on the measurement.

The output contains 262,144 int8 weights and no domains. A linear model over
hashed n-grams cannot be inverted to enumerate what it was trained on, which is
why it can exist in a repo that carries no domain in any encoding — but it is
still generated rather than committed, like every other list-derived artifact
here, so the same one command rebuilds everything.
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from domain_features import TABLE_SIZE, vectorize  # noqa: E402

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "packages" / "core" / "src" / "model"

# category -> (block rank, warn rank) in the held-out negatives.
#
# Two tiers, because "possibly" and "almost certainly" are different claims and
# only one of them may take a site away without asking.
#
# **warn** is how deep the ranking was reviewed and found free of genuine false
# positives. list2: all eight non-keyword names in its top 1200 are gambling on
# inspection (`bk8686.com`, `jiliphc8.com`, `kelas4d.vip`,
# `blackjackgiris.site`). list3 is held much shallower than its recall curve
# would allow because it degrades fast — 87% on-subject by rank 1000.
#
# **block** is deliberately far stricter than the review requires. Blocking on a
# guess is the one thing this model was not built to do, so the bar is not "no
# false positive was found" but "nothing near the boundary". It costs most of
# the recall — 24% down to 8% for gambling — and that is the trade: the other
# 16% still warns, and a warn the user clicks through is a far smaller failure
# than a site taken away wrongly.
SHIP = {"list2": (300, 1200), "list3": (200, 400)}

# Hosts pinned by every port's tests. Deliberately mixed: two that must score
# high, ordinary sites that must not, a many-labelled name, punycode, a shared
# platform, and the empty string.
#
# Every made-up name here was checked against all seven lists and is in none of
# them, and the real ones are famous sites or platform hosts. Nothing on this
# list is a blocklist entry, so pinning them in four test suites publishes
# nothing.
VECTORS = [
    "best-casino-slots-bonus.com",
    "adult-xxx-tube-videos.com",
    "github.com",
    "wikipedia.org",
    "nhs.uk",
    "acme-plumbing-services.com",
    "xn--test-punycode-9za.net",
    "a.b.c.example.co.uk",
    "3.bp.blogspot.com",
    "",
]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=os.environ.get("SAFE_WORLD_MODEL_WORK", ""))
    args = ap.parse_args()
    if not args.work:
        print("--work (or $SAFE_WORLD_MODEL_WORK) is required", file=sys.stderr)
        return 2
    work = Path(args.work).resolve()
    corpus, models = work / "corpus", work / "models"

    neg = [l for l in (corpus / "negatives.test.txt").read_text(encoding="utf8").splitlines() if l]
    neg_x = vectorize(neg)
    OUT.mkdir(parents=True, exist_ok=True)

    pinned: dict[str, dict[str, float]] = {}
    for cat, (block_rank, warn_rank) in SHIP.items():
        d = np.load(models / f"{cat}.npz")
        w, b = d["w"].astype(np.float32), float(d["b"])

        peak = float(np.abs(w).max())
        scale = peak / 127.0 if peak > 0 else 1.0
        q = np.clip(np.round(w / scale), -127, 127).astype(np.int8)

        # Quantised weights decide the threshold, because quantised weights are
        # what ship. Deriving it from the float model would leave every port
        # slightly off the operating point that was measured.
        dequant = q.astype(np.float32) * scale
        scores = np.sort((neg_x @ dequant) + b)[::-1]
        block_threshold = float(scores[block_rank - 1])
        warn_threshold = float(scores[warn_rank - 1])

        pos = [l for l in (corpus / f"{cat}.test.txt").read_text(encoding="utf8").splitlines() if l]
        blocked = warned = 0.0
        for i in range(0, len(pos), 200_000):
            s = (vectorize(pos[i : i + 200_000]) @ dequant) + b
            blocked += float((s >= block_threshold).sum())
            warned += float((s >= warn_threshold).sum())
        blocked /= max(1, len(pos))
        warned /= max(1, len(pos))

        payload = {
            "category": cat,
            "tableSize": TABLE_SIZE,
            "scale": scale,
            "bias": b,
            "blockThreshold": block_threshold,
            "threshold": warn_threshold,
            "weights": base64.b64encode(q.tobytes()).decode("ascii"),
        }
        path = OUT / f"{cat}.json"
        path.write_text(json.dumps(payload) + "\n", encoding="utf8")
        print(
            f"{cat}: block >={block_threshold:.6f} (rank {block_rank}, recall {blocked:.2%}) "
            f"warn >={warn_threshold:.6f} (rank {warn_rank}, recall {warned:.2%}) "
            f"{path.stat().st_size // 1024} KB"
        )

        for host in VECTORS:
            m = vectorize([host])
            pinned.setdefault(host, {})[cat] = float((m @ dequant)[0] + b)

    # The cross-port contract. Every port pins these and fails if it disagrees.
    (OUT / "vectors.json").write_text(
        json.dumps({"hosts": VECTORS, "scores": pinned}, indent=2) + "\n", encoding="utf8"
    )
    print(f"\npinned vectors for {len(VECTORS)} hosts -> {OUT / 'vectors.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
