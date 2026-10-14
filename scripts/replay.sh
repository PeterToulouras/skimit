#!/usr/bin/env bash
# Replays a repository's recent history through `skimit diff`: each commit against its parent.
# It answers the question that matters for adoption: of the changes this project really made,
# how many needed a careful read?
#
#   scripts/replay.sh <git-repo> <policy-file> [commits] [-- build command...]
#
# Each commit is checked out into a scratch worktree and built with the build command. By default
# that is a quiet compile with tests and style checks off: Maven when the commit has a pom.xml,
# otherwise Gradle (the project's own ./gradlew when it has one). Compiled classes are cached per
# commit, so a commit and its child share one build. The run can be interrupted and resumed.
#
# Environment:
#   SKIMIT_JAR   path to skimit.jar (default: the one built in this checkout)
#   REPLAY_WORK  scratch directory (default: <git-repo>/../<name>-replay)
#   REPLAY_TIMEOUT  seconds allowed per build (default 600)
#   REPLAY_COMMITS  replay exactly these commits (space-separated) instead of the most recent ones
#   REPLAY_TOOL  maven or gradle, to choose when a project has both builds (default: maven)
#
# Output: <work>/replay.jsonl, one line per commit; <work>/diffs/<sha>.json, the full manifest of
# each commit (scripts/fix-check.py reads these); and a summary on stdout.
set -uo pipefail
[ $# -ge 2 ] || { sed -n '2,21p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
repo="$(cd "$1" && pwd)"; policy="$(cd "$(dirname "$2")" && pwd)/$(basename "$2")"; shift 2
count=100
if [ $# -gt 0 ] && [ "$1" != "--" ]; then count="$1"; shift; fi
[ "${1:-}" = "--" ] && shift
build=("$@")
maven=(mvn -q -B -Dmaven.test.skip=true -Dcheckstyle.skip=true -Denforcer.skip=true
       -Dspring-javaformat.skip=true -Dlicense.skip=true -Dformatter.skip=true -Dimpsort.skip=true
       -Drewrite.skip=true -Dmaven.gitcommitid.skip=true compile)
# `classes` compiles main sources in every subproject; tests and checks are not part of it.
gradle_args=(-q classes)

# The build for the commit checked out in the worktree: the one given, or the project's own.
build_here() {
  if [ ${#build[@]} -gt 0 ]; then "${build[@]}"; return; fi
  local has_gradle=""
  [ -f build.gradle ] || [ -f build.gradle.kts ] || [ -f settings.gradle ] || [ -f settings.gradle.kts ] && has_gradle=1
  if [ -f pom.xml ] && { [ -z "$has_gradle" ] || [ "${REPLAY_TOOL:-maven}" = maven ]; }; then "${maven[@]}"
  elif [ -n "$has_gradle" ] && [ -x ./gradlew ]; then ./gradlew "${gradle_args[@]}"
  elif [ -n "$has_gradle" ]; then gradle "${gradle_args[@]}"
  else echo "replay: no pom.xml or Gradle build in this commit" >&2; return 1
  fi
}
here="$(cd "$(dirname "$0")/.." && pwd)"
jar="${SKIMIT_JAR:-$here/skimit-analyzer/target/skimit.jar}"
[ -f "$jar" ] || { echo "replay: $jar not found; run mvn package first" >&2; exit 2; }
work="${REPLAY_WORK:-$(dirname "$repo")/$(basename "$repo")-replay}"
tree="$work/tree"; cache="$work/classes"; out="$work/replay.jsonl"
mkdir -p "$cache" "$work/diffs"; touch "$out"
[ -d "$tree" ] || git -C "$repo" worktree add -q --detach -f "$tree" HEAD

# Builds one commit and caches its class directories; succeeds if the commit has classes.
classes_for() {
  local sha="$1" dir="$cache/$1"
  if [ ! -d "$dir" ]; then
    mkdir -p "$dir"
    git -C "$tree" checkout -q -f "$sha" && git -C "$tree" clean -qfdx
    if (cd "$tree" && export -f build_here && timeout "${REPLAY_TIMEOUT:-600}" bash -c "$(declare -p build maven gradle_args); build_here") > "$dir/build.log" 2>&1; then
      local n=0
      while IFS= read -r d; do n=$((n + 1)); cp -r "$d" "$dir/$n"; done < <(
        find "$tree" -type d \( -path '*/target/classes' -o -path '*/build/classes/java/main' \) -not -path '*/node_modules/*' | sort)
      [ "$n" -gt 0 ] || touch "$dir/FAILED"
    else
      touch "$dir/FAILED"
    fi
  fi
  [ ! -e "$dir/FAILED" ]
}
dirs_of() { find "$cache/$1" -mindepth 1 -maxdepth 1 -type d | sort; }

if [ -n "${REPLAY_COMMITS:-}" ]; then
  commits="$(for c in $REPLAY_COMMITS; do git -C "$repo" rev-parse "$c"; done)"
else
  commits="$(git -C "$repo" log --no-merges -n "$count" --format=%H -- ':(glob)**/src/main/java/**')"
fi
for sha in $commits; do
  grep -q "\"$sha\"" "$out" && continue
  parent="$(git -C "$repo" rev-parse -q --verify "$sha^")" || continue
  date="$(git -C "$repo" show -s --format=%cs "$sha")"
  if ! classes_for "$sha" || ! classes_for "$parent"; then
    echo "{\"sha\":\"$sha\",\"date\":\"$date\",\"skipped\":\"did not build\"}" >> "$out"; continue
  fi
  # shellcheck disable=SC2046
  if java -jar "$jar" diff --base $(dirs_of "$parent") --head $(dirs_of "$sha") --policy "$policy" --json "$work/diffs/$sha.json" > /dev/null 2> "$work/diff.err"; then
    python3 - "$sha" "$date" "$work/diffs/$sha.json" >> "$out" <<'PY'
import json, sys
d = json.load(open(sys.argv[3]))
print(json.dumps({"sha": sys.argv[1], "date": sys.argv[2], "must": d["mustReview"], "should": d["shouldReview"],
                  "isolated": d["isolated"], "policy": len(d["policy"]), "shape": len(d["shape"])}))
PY
  else
    echo "{\"sha\":\"$sha\",\"date\":\"$date\",\"skipped\":\"analysis failed\"}" >> "$out"
  fi
done

python3 - "$out" <<'PY'
import json, sys
rows = [json.loads(l) for l in open(sys.argv[1]) if l.strip()]
ok = [r for r in rows if "skipped" not in r]
if not ok:
    print("replay: no commit could be analysed; see the build.log files under the work directory"); sys.exit(1)
changed = [r for r in ok if r["must"] + r["should"] + r["isolated"] > 0]
must = [r for r in changed if r["must"] > 0 or r["policy"] > 0]
should = [r for r in changed if r not in must and r["should"] > 0]
iso = [r for r in changed if r not in must and r not in should]
n = len(ok)
pct = lambda k: f"{100 * k / n:.0f}%"
print(f"{len(rows)} commits, {n} analysed, {len(rows) - n} skipped ({min(r['date'] for r in rows)} to {max(r['date'] for r in rows)})")
print(f"  no bytecode change      {n - len(changed):4d}  {pct(n - len(changed))}")
print(f"  only isolated methods   {len(iso):4d}  {pct(len(iso))}")
print(f"  should review           {len(should):4d}  {pct(len(should))}")
print(f"  must review             {len(must):4d}  {pct(len(must))}")
m, s, i = (sum(r[k] for r in changed) for k in ("must", "should", "isolated"))
print(f"  changed methods: {m + s + i} ({m} must, {s} should, {i} isolated)")
PY
