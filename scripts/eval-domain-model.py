#!/usr/bin/env python3
"""Measure the advisory model, per category. This is the gate.

    python3 scripts/eval-domain-model.py --work /tmp/sw-model

The table it prints decides which categories ship. From the plan:

- more than ~10 false positives per million top-sites domains disqualifies a
  category — above that people turn protection off and lose the exact-list
  blocking too, which is a net loss;
- recall below ~15% on the held-out split does not earn the size, the
  complexity, or four hand-written ports;
- categories are enabled individually, never all-or-nothing.

**Read "held out" precisely: it is a grouped split, not a temporal one.** The
plan called for training on `baseline/baseline.json` and testing on what has been
added since, which is the honest test of "would it have caught a name nobody had
seen". That turned out to be impossible here — see the note in the plan — so the
split instead guarantees no registrable domain, and therefore no sibling
subdomain, appears on both sides. It answers "would it catch a name unrelated to
anything it trained on", which is weaker but is not self-flattering.

Three columns exist because one number would hide the specific way this model
fails. `tail` is Majestic ranks 500k-1M — the closest thing available to
ordinary long-tail sites. Blocklist domains are long and hyphenated and top-1M
domains are short and brandy, so a model can score beautifully by learning
"looks obscure" rather than the subject, and that failure shows up as false
positives on the tail while the headline number stays clean.

**The false-positive columns are wrong until adjudicated, and by a lot.** The
negatives are top-sites minus our lists, so anything our lists *miss* is
labelled allowed. Majestic's top million is full of gambling and pharmacy sites
we do not carry — 4.8% of it was already dropped for being on a list, and what
survives is not clean. The measured false positives for list2 were, on
inspection, `jojobet-casino.top`, `222.casino`, `1xbet-48.com` and thirty-seven
more of the same: the model is right and the list is short. So a raw FP rate
here measures list coverage, not model error, and reads far worse than the truth.

`--dump-top` writes the highest-scoring negatives for review; a reviewed file
under `--adjudication` marks each `blocked` or `allowed`, and the adjudicated
columns count only the ones a human called `allowed`. **The reviewed files hold
domains, so they live in the work directory and never in this repo** — the
procedure is committed, the data is not.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from domain_features import vectorize  # noqa: E402

CATEGORIES = ["list1", "list2", "list3", "list4", "list5", "list6", "list7"]
CHUNK = 200_000
REPO = Path(__file__).resolve().parent.parent

# Names that must never be advised against, whatever the numbers say elsewhere.
# Deliberately ordinary: a bank, a health service, a journal, a code host — the
# sites where one wrong interstitial ends the user's trust in the whole product.
ADVERSARIAL = [
    "github.com", "wikipedia.org", "nhs.uk", "chase.com", "sciencedirect.com",
    "gov.uk", "who.int", "stackoverflow.com", "archive.org", "bbc.co.uk",
    "mail.google.com", "openstreetmap.org", "python.org", "kernel.org",
    "citibank.com", "irs.gov", "nih.gov", "un.org", "redcross.org",
]


def read_lines(path: Path) -> list[str]:
    return [l for l in path.read_text(encoding="utf8").splitlines() if l]


def score(hosts: list[str], w: np.ndarray, b: float) -> np.ndarray:
    out = np.empty(len(hosts), dtype=np.float32)
    for i in range(0, len(hosts), CHUNK):
        m = vectorize(hosts[i : i + CHUNK])
        out[i : i + m.shape[0]] = (m @ w) + b
    return out


def threshold_for(neg_scores: np.ndarray, per_million: float) -> tuple[float, int]:
    """Smallest score admitting at most `per_million` of the negatives."""
    n = neg_scores.size
    allowed = max(0, int(np.floor(per_million * n / 1_000_000)))
    ordered = np.sort(neg_scores)[::-1]
    if allowed >= n:
        return float(ordered[-1]), n
    thr = float(ordered[allowed]) + 1e-6
    return thr, int((neg_scores >= thr).sum())


def load_adjudication(path: Path) -> dict[str, str]:
    """`<domain>\\tblocked|allowed` per line; `#` comments ignored."""
    if not path.exists():
        return {}
    out: dict[str, str] = {}
    for line in path.read_text(encoding="utf8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        host, _, verdict = line.partition("\t")
        if verdict.strip() in ("blocked", "allowed"):
            out[host.strip()] = verdict.strip()
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=os.environ.get("SAFE_WORLD_MODEL_WORK", ""))
    ap.add_argument("--categories", default=",".join(CATEGORIES))
    ap.add_argument("--dump-top", type=int, default=0, help="write the N top-scoring negatives for review")
    args = ap.parse_args()
    if not args.work:
        print("--work (or $SAFE_WORLD_MODEL_WORK) is required", file=sys.stderr)
        return 2
    work = Path(args.work).resolve()
    corpus, models = work / "corpus", work / "models"
    adjudged_dir = work / "adjudication"

    neg = read_lines(corpus / "negatives.test.txt")
    tail = read_lines(corpus / "negatives.tail.txt")
    print(f"held-out negatives {len(neg)}, tail negatives {len(tail)}\n")

    if args.dump_top:
        adjudged_dir.mkdir(parents=True, exist_ok=True)

    header = (
        f"{'cat':6} {'test+':>8} {'recall@10/M':>12} {'recall@100/M':>13} "
        f"{'recall@1000/M':>14} {'tailFP/M':>9} {'AUC':>7} {'int8Δ':>8} {'adjudicated':>22}"
    )
    print(header)
    print("-" * len(header))

    rows = []
    clean_col: list[tuple[str, str]] = []
    for cat in args.categories.split(","):
        npz_path = models / f"{cat}.npz"
        if not npz_path.exists():
            continue
        d = np.load(npz_path)
        w, b = d["w"].astype(np.float32), float(d["b"])

        pos = read_lines(corpus / f"{cat}.test.txt")
        if not pos:
            print(f"{cat:6} {'0':>8}  (no held-out positives — see the split note)")
            continue

        s_pos = score(pos, w, b)
        s_neg = score(neg, w, b)
        s_tail = score(tail, w, b)

        recalls = {}
        thr10 = None
        for per_m in (10, 100, 1000):
            thr, actual = threshold_for(s_neg, per_m)
            recalls[per_m] = float((s_pos >= thr).mean())
            if per_m == 10:
                thr10, fp10 = thr, actual
        tail_fp = float((s_tail >= thr10).mean()) * 1_000_000

        from sklearn.metrics import roc_auc_score

        y = np.concatenate([np.ones(s_pos.size, np.int8), np.zeros(s_neg.size, np.int8)])
        auc = roc_auc_score(y, np.concatenate([s_pos, s_neg]))

        # What quantisation costs. If this is not tiny the int8 export is wrong,
        # not merely lossy — the same trap the gender model hit.
        q = np.fromfile(models / f"{cat}.int8.bin", dtype=np.int8).astype(np.float32)
        scale = float(np.abs(w).max()) / 127.0
        s_pos_q = score(pos[:20000], q * scale, b)
        int8_delta = float(np.abs(s_pos_q - s_pos[:20000]).max())

        order = np.argsort(s_neg)[::-1]
        if args.dump_top:
            lines = [
                "# Mark each: <domain>\\tblocked   (the model is right, our list is short)",
                "#            <domain>\\tallowed   (a real false positive)",
                "# Highest-scoring held-out negatives first.",
            ]
            lines += [f"{neg[i]}\t" for i in order[: args.dump_top]]
            (adjudged_dir / f"{cat}.tsv").write_text("\n".join(lines) + "\n", encoding="utf8")

        verdicts = load_adjudication(adjudged_dir / f"{cat}.tsv")
        if verdicts:
            reviewed = [neg[i] for i in order[: len(verdicts) + 200] if neg[i] in verdicts]
            wrong = sum(1 for h in reviewed if verdicts[h] == "allowed")
            adj = f"{len(reviewed) - wrong}/{len(reviewed)} correct"

            # The operating point the adjudication actually licenses: just above
            # the highest-scoring negative a human called a real false positive.
            # Everything the model ranks above that line was checked and found to
            # belong on a list. This is the recall the category can be shipped at,
            # and it is far above the raw columns, which are held down by the
            # gambling and pharmacy sites Majestic carries and our lists do not.
            fp_scores = [s_neg[i] for i in order if neg[i] in verdicts and verdicts[neg[i]] == "allowed"]
            if fp_scores:
                clean_thr = float(max(fp_scores)) + 1e-6
                clean_recall = float((s_pos >= clean_thr).mean())
                admitted = int((s_neg >= clean_thr).sum())
                clean = f"{clean_recall:.2%} @ {admitted * 1_000_000 // len(neg)}/M raw"
            else:
                clean = "no FP found"
        else:
            adj, clean = "-", "-"
        clean_col.append((cat, clean))

        print(
            f"{cat:6} {len(pos):>8} {recalls[10]:>11.1%} {recalls[100]:>12.1%} "
            f"{recalls[1000]:>13.1%} {tail_fp:>9.0f} {auc:>7.4f} {int8_delta:>8.2e} {adj:>22}"
        )
        rows.append((cat, thr10, w, b))

    print("\nrecall at the highest threshold the review clears — no reviewed false positive")
    print("above it, and the raw figure is what that admits, nearly all of them correct catches:")
    for cat, clean in clean_col:
        print(f"  {cat:6} {clean}")

    print("\nadversarial set, at the 10/M threshold (any 'BLOCK' fails the gate):")
    for cat, thr, w, b in rows:
        s = score(ADVERSARIAL, w, b)
        bad = [f"{h}={v:.2f}" for h, v in zip(ADVERSARIAL, s) if v >= thr]
        print(f"  {cat:6} {'BLOCK ' + ', '.join(bad) if bad else 'clear'}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
