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

**Three sources, and the third is the one that matters.** The first pass used
Majestic's top million alone and the model learned "looks obscure" as much as it
learned any subject — unsurprising, because every negative it had ever seen was a
short brandy name with global reach. The domains it must not flag are nothing
like that: they are `1752solutions.com` and `247lightheartedcaregivers.com`,
ordinary small businesses that appear in no top-million list. DomCop's Open
PageRank 10M supplies them, Tranco adds a differently-aggregated top million, and
the tier each source lands in is kept so false positives on the long tail can be
reported separately from false positives on famous sites.

Sources are training input and are never redistributed: Majestic Million
(CC BY 3.0), Tranco (academic, free), DomCop Open PageRank (free with
attribution). Only derived weights would ship.

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

# Training negatives are capped because the feature matrix, not the disk, is the
# limit: ~80 non-zeros per host means every extra million negatives costs about
# 640 MB. Evaluation uses every held-out negative, since scoring runs in chunks
# and never materialises one matrix — which is the half that needed to grow
# anyway, because 10 false positives per million is not measurable against
# 190,000 negatives.
NEG_TRAIN_CAP = 2_000_000

# Tier 0 is famous, tier 2 is the long tail. Reported separately: a model that
# has learned "obscure" rather than "gambling" is clean on tier 0 and bleeds on
# tier 2, and one blended number hides that completely.
TIER_FAMOUS, TIER_MID, TIER_TAIL = 0, 1, 2
DOMCOP_MID_RANK = 2_000_000


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
    # host -> tier, keeping the most famous tier a host appears in.
    tiers: dict[str, int] = {}

    def offer(host: str, tier: int) -> None:
        host = normalize(host)
        if not host:
            return
        current = tiers.get(host)
        if current is None or tier < current:
            tiers[host] = tier

    majestic = data / "majestic.csv"
    if majestic.exists():
        with majestic.open(encoding="utf8", newline="") as fh:
            for row in csv.DictReader(fh):
                offer(row["Domain"], TIER_FAMOUS)
        print(f"after majestic: {len(tiers)}")

    tranco = data / "top-1m.csv"
    if tranco.exists():
        with tranco.open(encoding="utf8", newline="") as fh:
            for row in csv.reader(fh):
                if len(row) >= 2:
                    offer(row[1], TIER_FAMOUS)
        print(f"after tranco: {len(tiers)}")

    domcop = data / "top10milliondomains.csv"
    if domcop.exists():
        with domcop.open(encoding="utf8", newline="") as fh:
            reader = csv.reader(fh)
            next(reader, None)
            for row in reader:
                if len(row) < 2:
                    continue
                rank = int(row[0])
                offer(row[1], TIER_MID if rank <= DOMCOP_MID_RANK else TIER_TAIL)
        print(f"after domcop: {len(tiers)}")

    negatives: list[tuple[int, str]] = []
    dropped = 0
    for host, tier in tiers.items():
        if registrable(host, suffixes) in blocked_registrable:
            dropped += 1
            continue
        negatives.append((tier, host))
    del tiers
    negatives.sort(key=lambda t: (t[0], t[1]))
    by_tier = [sum(1 for t, _ in negatives if t == k) for k in (0, 1, 2)]
    print(f"negatives: {len(negatives)} kept, {dropped} dropped as blocked")
    print(f"  famous {by_tier[0]}, mid {by_tier[1]}, tail {by_tier[2]}")

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
    for tier, host in negatives:
        if in_test(group_key(host, suffixes), args.test_percent):
            neg_test.append(host)
            if tier == TIER_TAIL:
                neg_tail.append(host)
        else:
            neg_train.append(host)

    # Sampled across the whole pool rather than truncated, or the cap would take
    # the famous tier and none of the long tail — throwing away the reason for
    # adding it.
    if len(neg_train) > NEG_TRAIN_CAP:
        keep = NEG_TRAIN_CAP / len(neg_train)
        cutoff = int(keep * (1 << 32))
        neg_train = [
            h
            for h in neg_train
            if int.from_bytes(hashlib.sha256(("neg:" + h).encode("utf8")).digest()[:4], "big") < cutoff
        ]

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
