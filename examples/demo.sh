#!/usr/bin/env bash
# Builds the example shop twice (before and after examples/pull-request.patch) and prints the
# review manifest for the change. Run from anywhere after `mvn package` at the repo root.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
jar="$root/skimit-analyzer/target/skimit.jar"
agent="$root/skimit-agent/target/skimit-agent.jar"
ann="$(ls "$root"/skimit-annotations/target/skimit-annotations-*.jar | head -1)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

cp -r "$root/examples/shop" "$work/base"
cp -r "$root/examples/shop" "$work/head"
patch -s -p1 -d "$work/head" < "$root/examples/pull-request.patch"

for v in base head; do
  javac -cp "$ann" -d "$work/$v/classes" $(find "$work/$v/src" -name '*.java')
done

echo "=== analyze (base) ==="
java -jar "$jar" analyze "$work/base/classes" --list
echo
echo "=== diff (base -> head) ==="
java -jar "$jar" diff --base "$work/base/classes" --head "$work/head/classes" --html "$root/skimit-report.html"
echo
echo "Interactive report written to $root/skimit-report.html"
echo
echo "=== verify (run the shop for real and compare with the verdicts) ==="
java -jar "$jar" analyze "$work/base/classes" --agent-config "$work/agent.conf" > /dev/null
java "-javaagent:$agent=config=$work/agent.conf,out=$work/trace.txt" -cp "$work/base/classes" shop.Demo
java -jar "$jar" verify "$work/base/classes" --trace "$work/trace.txt"
