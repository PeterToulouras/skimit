#!/usr/bin/env bash
# Builds the example shop twice, before and after examples/pull-request.patch, and shows what
# skimit says: what the shop does, what the change did to that, and a real run checked against
# the map. Run from anywhere after `mvn package` at the repo root.
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

# skimit reads the shop's own classes. The Spring annotations are stand-ins, and run/ drives the
# shop the way a test would; both are compiled beside it, not into it.
for v in base head; do
  javac -cp "$ann" -d "$work/$v/spring-classes" $(find "$work/$v/spring" -name '*.java')
  javac -cp "$ann:$work/$v/spring-classes" -d "$work/$v/classes" $(find "$work/$v/src" -name '*.java')
  javac -cp "$work/$v/classes" -d "$work/$v/run-classes" $(find "$work/$v/run" -name '*.java')
done

echo "=== map: what the shop does ==="
java -jar "$jar" map "$work/base/classes"
echo
echo "=== diff: what the pull request did to that ==="
java -jar "$jar" diff --base "$work/base/classes" --head "$work/head/classes" --policy "$root/examples/shop/shop.policy" \
  --diff "$root/examples/pull-request.patch" --html "$root/skimit.html"
echo
echo "The page is at $root/skimit.html"
echo
echo "=== verify: run the shop for real and check the run against the map ==="
java -jar "$jar" map "$work/base/classes" --agent-config "$work/agent.conf" > /dev/null
java "-javaagent:$agent=config=$work/agent.conf,out=$work/trace.txt" \
  -cp "$work/base/classes:$work/base/spring-classes:$work/base/run-classes" shop.Demo
java -jar "$jar" verify "$work/base/classes" --trace "$work/trace.txt"
