#!/usr/bin/env python3
"""Train the advisory domain model — one linear classifier per category.

Reads the splits written by `prepare-model-corpus.py`, writes float weights for
evaluation plus the int8 export that would ship.

    python3 scripts/train-domain-model.py --work /tmp/sw-model

**This is deliberately not a neural net.** The model is hashed character n-grams
(`domain_features.py`) into a logistic regression: ~30 lines of inference in
TypeScript, Swift, Kotlin and C# with no ML runtime, against a char-CNN needing
TFLite or ONNX in four places when only Android has a runtime today. It is also
the version that provably **cannot memorise the list** — a linear model over
hashed n-grams has no way to reproduce a domain, which matters because this repo
ships no domain data in any encoding and these weights are derived from the
lists.

Two knobs that are not free choices:

- `MAX_TRAIN_POSITIVES` caps list1 at a fraction of its 2.5M training rows.
  Logistic regression over a 2^18 feature table saturates long before then, and
  the full matrix does not fit in memory. Sampling is by stable hash, not by
  slicing, because the lists arrive in feed order and the head of list1 is one
  feed's worth of near-identical names.
- `class_weight="balanced"` matters more than it looks. Positives outnumber
  negatives 3:1 for list1 and are outnumbered 30:1 for list7; without it the
  small categories train to predict "allowed" for everything and score 99%.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from domain_features import TABLE_SIZE, vectorize  # noqa: E402

CATEGORIES = ["list1", "list2", "list3", "list4", "list5", "list6", "list7"]
MAX_TRAIN_POSITIVES = 500_000
CHUNK = 200_000
REPO = Path(__file__).resolve().parent.parent


def read_lines(path: Path) -> list[str]:
    return [l for l in path.read_text(encoding="utf8").splitlines() if l]


def subsample(hosts: list[str], limit: int, tag: str) -> list[str]:
    """Stable hash sample, so two runs train on the same rows."""
    if len(hosts) <= limit:
        return hosts
    keep = limit / len(hosts)
    cutoff = int(keep * (1 << 32))
    out = []
    for h in hosts:
        d = hashlib.sha256((tag + ":" + h).encode("utf8")).digest()
        if int.from_bytes(d[:4], "big") < cutoff:
            out.append(h)
    return out


def build_matrix(hosts: list[str]):
    from scipy.sparse import vstack

    parts = []
    for i in range(0, len(hosts), CHUNK):
        m = vectorize(hosts[i : i + CHUNK])
        m.data = m.data.astype(np.float32)
        parts.append(m)
    return vstack(parts, format="csr") if len(parts) > 1 else parts[0]


def quantize(weights: np.ndarray) -> tuple[np.ndarray, float]:
    """int8 with one shared scale — 262 KB per category on device.

    A per-block scale would be tighter but every port would have to reproduce
    the blocking exactly. One scale is one multiply.
    """
    peak = float(np.abs(weights).max())
    if peak == 0.0:
        return np.zeros_like(weights, dtype=np.int8), 1.0
    scale = peak / 127.0
    return np.clip(np.round(weights / scale), -127, 127).astype(np.int8), scale


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=os.environ.get("SAFE_WORLD_MODEL_WORK", ""))
    ap.add_argument("--categories", default=",".join(CATEGORIES))
    ap.add_argument("--epochs", type=int, default=8)
    args = ap.parse_args()

    if not args.work:
        print("--work (or $SAFE_WORLD_MODEL_WORK) is required", file=sys.stderr)
        return 2
    work = Path(args.work).resolve()
    if REPO == work or REPO in work.parents:
        print(f"refusing to write model artifacts inside the repo: {work}", file=sys.stderr)
        return 2

    from sklearn.linear_model import SGDClassifier
    from scipy.sparse import vstack

    corpus = work / "corpus"
    models = work / "models"
    models.mkdir(parents=True, exist_ok=True)

    neg = read_lines(corpus / "negatives.train.txt")
    print(f"negatives: {len(neg)}")
    t0 = time.time()
    neg_x = build_matrix(neg)
    print(f"negatives featurised in {time.time() - t0:.0f}s, nnz {neg_x.nnz}")

    for cat in args.categories.split(","):
        pos = read_lines(corpus / f"{cat}.train.txt")
        sampled = subsample(pos, MAX_TRAIN_POSITIVES, cat)
        print(f"\n=== {cat}: {len(pos)} positives, training on {len(sampled)} ===")

        t0 = time.time()
        pos_x = build_matrix(sampled)
        x = vstack([pos_x, neg_x], format="csr")
        y = np.concatenate([np.ones(pos_x.shape[0], np.int8), np.zeros(neg_x.shape[0], np.int8)])
        print(f"  matrix {x.shape} nnz {x.nnz} in {time.time() - t0:.0f}s")

        clf = SGDClassifier(
            loss="log_loss",
            penalty="l2",
            alpha=1e-7,
            max_iter=args.epochs,
            tol=None,
            class_weight="balanced",
            random_state=17,
        )
        t0 = time.time()
        clf.fit(x, y)
        w = clf.coef_.ravel().astype(np.float32)
        b = float(clf.intercept_[0])
        print(f"  fit in {time.time() - t0:.0f}s, |w|max {np.abs(w).max():.4f}, bias {b:.4f}")

        q, scale = quantize(w)
        np.savez_compressed(models / f"{cat}.npz", w=w, b=np.float32(b))
        (models / f"{cat}.int8.json").write_text(
            json.dumps(
                {
                    "category": cat,
                    "table_size": TABLE_SIZE,
                    "scale": scale,
                    "bias": b,
                    "weights_b64": None,
                },
                indent=2,
            )
            + "\n",
            encoding="utf8",
        )
        q.tofile(models / f"{cat}.int8.bin")
        print(f"  int8 {(models / f'{cat}.int8.bin').stat().st_size / 1024:.0f} KB, scale {scale:.3e}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
