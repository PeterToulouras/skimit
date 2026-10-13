#!/usr/bin/env python3
"""Checks replayed verdicts against the project's own bug fixes.

    scripts/fix-check.py <git-repo> <replay-work-dir>

Run scripts/replay.sh first. For every replayed commit whose message says it fixes something, this
looks at each method the fix had to change on a critical path, finds the last earlier commit in
the replayed window that changed the same method, and reports how that earlier change was
classified. If the earlier change was "isolated", a reviewer following the manifest would have
skimmed code that later needed a fix on a critical path: that is a miss.

The last change to a method is a stand-in for the change that introduced the bug, as in the SZZ
method; it is evidence, not proof.
"""
import json, os, re, subprocess, sys

repo, work = sys.argv[1], sys.argv[2]
FIX = re.compile(r"\b(fix|fixes|fixed|bug|defect|regression|hotfix|npe)\b|修复|修正|问题|异常|漏洞", re.I)

rows = [json.loads(l) for l in open(os.path.join(work, "replay.jsonl")) if l.strip()]
shas = [r["sha"] for r in rows if "skipped" not in r]            # newest first
changes = {}
for sha in shas:
    path = os.path.join(work, "diffs", sha + ".json")
    if os.path.exists(path):
        changes[sha] = {c["method"]: c for c in json.load(open(path))["changes"]}
subject = lambda sha: subprocess.run(["git", "-C", repo, "show", "-s", "--format=%s", sha],
                                     capture_output=True, text=True).stdout.strip()

fixes = [s for s in shas if s in changes and FIX.search(subject(s))]
tally = {"MUST": 0, "SHOULD": 0, "SKIP": 0, "not in window": 0}
misses = []
critical_fixes = 0
wider = {"MUST": 0, "SHOULD": 0, "SKIP": 0, "not in window": 0}
for f in fixes:
    flagged = [c for c in changes[f].values() if c["bucket"] in ("MUST", "SHOULD") and c["kind"] == "changed"]
    if any(c["bucket"] == "MUST" for c in flagged):
        critical_fixes += 1
    for c in flagged:
        earlier = next((e for e in shas[shas.index(f) + 1:] if c["method"] in changes.get(e, {})), None)
        bucket = changes[earlier][c["method"]]["bucket"] if earlier else "not in window"
        wider[bucket] += 1
        if c["bucket"] != "MUST":
            continue
        tally[bucket] += 1
        if bucket == "SKIP":
            misses.append((c["name"], f[:8], earlier[:8]))

print(f"{len(shas)} replayed commits, {len(fixes)} of them fixes, {critical_fixes} fixing code on a critical path")
total = sum(tally.values())
print(f"{total} methods fixed on a critical path. Their previous change had been classified:")
for k, label in (("MUST", "must review"), ("SHOULD", "should review"), ("SKIP", "isolated (a miss)"),
                 ("not in window", "no earlier change in the replayed window")):
    print(f"  {label:42s} {tally[k]:4d}")
print(f"Counting methods a fix changed in any flagged code ({sum(wider.values())}): "
      f"{wider['MUST']} must, {wider['SHOULD']} should, {wider['SKIP']} isolated, {wider['not in window']} with no earlier change")
for name, fix, earlier in misses:
    print(f"  MISS {name}: fixed in {fix}, last changed in {earlier} as isolated")
sys.exit(1 if misses else 0)
