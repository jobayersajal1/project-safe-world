#!/usr/bin/env python3
"""Build the train/test corpus for the advisory domain model.

Run this before `train-domain-model.py`. It reads the working blocklists
(`npm run lists:pull` must have run) plus a downloaded copy of Majestic Million,
and writes plain-text splits into a work directory.

**The work directory must be outside this repo.** Everything it writes is domain
data, which this repo never contains in any encoding. `--work` defaults to
`$SAFE_WORLD_MODEL_WORK`, and the script refuses a path inside the repo.

    python3 scripts/prepare-model-corpus.py --work /tmp/sw-model --data /tmp/sw-model/data

Two things in here are not obvious and are the whole reason it is a separate
step rather than a few lines inside the trainer.

**Negatives are top-sites minus every category, subtracted by registrable
domain.** Top-site lists are full of adult and gambling domains — several sit in
the global top 100 — so using them raw teaches the model that the biggest porn
sites are benign. Subtracting by registrable domain rather than by exact host
also keeps `www.<blocked>` and every sibling subdomain out, and guarantees no
registrable domain lands on both sides of the label.

**The split is grouped, not random.** 19.4% of list1 shares a registrable domain
with another entry — `webflow.io`, `duckdns.org`, `000webhostapp.com` and other
shared hosting account for tens of thousands each. A random split puts
`a.webflow.io` in train and `b.webflow.io` in test, and the model scores well by
recognising the host it already memorised. Every domain sharing a group goes to
the same side, so the test set is made of names the model has not seen a
relative of.

The group key is deliberately *coarser* than the Public Suffix List's registrable
domain. The PSL's private section lists `webflow.io` itself as a suffix, which
would make every `*.webflow.io` its own group and reintroduce exactly the leak
this exists to stop. So the key is the last two labels, extended to three only
when the last two are themselves a public suffix (`co.uk`, `com.br`).
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
import sys
from pathlib import Path

CATEGORIES = ["list1", "list2", "list3", "list4", "list5", "list6", "list7"]

REPO = Path(__file__).resolve().parent.parent


def load_public_suffixes(path: Path) -> set[str]:
    """Suffix strings from the PSL, wildcards and exceptions dropped.

    We only need it to tell `com.br` (a suffix) from `webflow.io` (which we
    deliberately treat as an ordinary two-label name — see the module docstring),
    so the rule handling that `!` and `*.` entries encode is more precision than
    the grouping needs.
    """
    suffixes: set[str] = set()
    for line in path.read_text(encoding="utf8").splitlines():
        line = line.strip()
        if not line or line.startswith("//"):
            continue
        suffixes.add(line.lstrip("*.").lstrip("!"))
    return suffixes


def group_key(host: str, suffixes: set[str]) -> str:
    """The unit the train/test split moves as a whole."""
    parts = host.split(".")
    if len(parts) <= 2:
        return host
    last_two = ".".join(parts[-2:])
    if last_two in suffixes:
        return ".".join(parts[-3:])
    return last_two


def registrable(host: str, suffixes: set[str]) -> str:
    """Longest matching public suffix plus one label — the PSL rule.

    Used only to subtract positives from the negatives, where being generous
    costs a negative and being stingy costs a *wrong* negative.
    """
    parts = host.split(".")
    for i in range(len(parts)):
        if ".".join(parts[i:]) in suffixes:
            return ".".join(parts[max(0, i - 1):])
    return ".".join(parts[-2:]) if len(parts) >= 2 else host


def normalize(host: str) -> str:
    host = host.strip().lower().rstrip(".")
    if host.startswith("www."):
        host = host[4:]
    return host


def in_test(key: str, test_percent: int) -> bool:
    """Stable group assignment: the same name always lands on the same side.

    Hashing rather than shuffling means adding a category or re-running with
    more data does not reshuffle what was previously in train, so two runs stay
    comparable.
    """
    digest = hashlib.sha256(("sw-split:" + key).encode("utf8")).digest()
    return int.from_bytes(digest[:4], "big") % 100 < test_percent


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", default=os.environ.get("SAFE_WORLD_MODEL_WORK", ""))
    ap.add_argument("--data", default="", help="where majestic.csv and public_suffix_list.dat are")
    ap.add_argument("--test-percent", type=int, default=20)
    args = ap.parse_args()

    if not args.work:
        print("--work (or $SAFE_WORLD_MODEL_WORK) is required", file=sys.stderr)
        return 2
    work = Path(args.work).resolve()
    if REPO == work or REPO in work.parents:
        print(f"refusing to write domain data inside the repo: {work}", file=sys.stderr)
        return 2
    data = Path(args.data).resolve() if args.data else work / "data"
    work.mkdir(parents=True, exist_ok=True)

    suffixes = load_public_suffixes(data / "public_suffix_list.dat")
    print(f"public suffixes: {len(suffixes)}")

    # ---------------------------------------------------------------- positives
    positives: dict[str, list[str]] = {}
    blocked_registrable: set[str] = set()
    for cat in CATEGORIES:
        path = REPO / "packages" / "core" / "src" / "blocklists" / f"{cat}.json"
        domains = json.loads(path.read_text(encoding="utf8"))["domains"]
        hosts = sorted({normalize(d) for d in domains if d})
        positives[cat] = hosts
        for h in hosts:
            blocked_registrable.add(registrable(h, suffixes))
        print(f"{cat}: {len(hosts)} positives")
    print(f"registrable domains covered by some category: {len(blocked_registrable)}")

    # ---------------------------------------------------------------- negatives
    negatives: list[tuple[int, str]] = []
    dropped = 0
    with (data / "majestic.csv").open(encoding="utf8", newline="") as fh:
        for row in csv.DictReader(fh):
            host = normalize(row["Domain"])
            if not host:
                continue
            if registrable(host, suffixes) in blocked_registrable:
                dropped += 1
                continue
            negatives.append((int(row["GlobalRank"]), host))
    print(f"negatives: {len(negatives)} kept, {dropped} dropped as blocked")

    # ------------------------------------------------------------------- splits
    out = work / "corpus"
    out.mkdir(parents=True, exist_ok=True)

    def write(name: str, rows: list[str]) -> None:
        (out / name).write_text("\n".join(rows) + "\n", encoding="utf8")

    for cat in CATEGORIES:
        train, test = [], []
        for host in positives[cat]:
            (test if in_test(group_key(host, suffixes), args.test_percent) else train).append(host)
        write(f"{cat}.train.txt", train)
        write(f"{cat}.test.txt", test)
        print(f"{cat}: train {len(train)} / test {len(test)}")

    neg_train, neg_test, neg_tail = [], [], []
    for rank, host in negatives:
        if in_test(group_key(host, suffixes), args.test_percent):
            neg_test.append(host)
        else:
            neg_train.append(host)
        # The bottom of the ranking is the closest thing we have to long-tail
        # legitimate names. Top-1M negatives are short brandy names, so a model
        # trained only against them can score well by learning "long and
        # hyphenated" — which is what would produce false positives on ordinary
        # small sites. This slice is reported separately so that failure shows.
        if rank > 500_000:
            neg_tail.append(host)
    write("negatives.train.txt", neg_train)
    write("negatives.test.txt", neg_test)
    write("negatives.tail.txt", neg_tail)
    print(f"negatives: train {len(neg_train)} / test {len(neg_test)} / tail {len(neg_tail)}")

    (out / "meta.json").write_text(
        json.dumps(
            {
                "test_percent": args.test_percent,
                "categories": CATEGORIES,
                "negatives_source": "Majestic Million (CC BY 3.0)",
                "split": "grouped by coarse registrable domain, hashed",
            },
            indent=2,
        )
        + "\n",
        encoding="utf8",
    )
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
