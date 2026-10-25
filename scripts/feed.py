#!/usr/bin/env python3
"""Turns a replay's work directory into one JSON line per commit, as experiments/2026-10/feed-* keeps them.

    scripts/feed.py <replay-work-dir> > feed.jsonl

Each line has the commit, its date, the headline, the counts, and every line skimit printed as
[kind, text], or why the commit was skipped.
"""
import json
import sys
from pathlib import Path

work = Path(sys.argv[1])
for raw in (work / "replay.jsonl").read_text().splitlines():
    if not raw.strip():
        continue
    row = json.loads(raw)
    if "skipped" in row:
        print(json.dumps(row))
        continue
    d = json.loads((work / "diffs" / f"{row['sha']}.json").read_text())
    print(json.dumps({"sha": row["sha"], "date": row["date"], "headline": d["headline"], "broken": d["broken"],
                      "what": d["what"], "how": d["how"], "lines": [[l["kind"], l["text"]] for l in d["lines"]]}))
