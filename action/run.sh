#!/usr/bin/env bash
# Runs skimit on a pull request: builds the base and the head, compares them, and writes the
# review manifest to the job summary. Called by action.yml; it can also be run by hand:
#
#   action/run.sh <base-sha>          (from the root of a checkout of the head)
#
# Settings come from the environment, as action.yml passes them:
#   SKIMIT_VERSION   release to download, or "latest" (default), or a path to a skimit.jar
#   SKIMIT_BUILD     build command; default: a quiet compile with Maven, or Gradle when there is no pom.xml
#   SKIMIT_POLICY    policy file, relative to the repository root (optional)
#   SKIMIT_FAIL      "true" to fail when anything lands in must review
#   SKIMIT_COMMENT   "true" to post the manifest as a pull request comment (needs GH_TOKEN and PR_NUMBER)
#   SKIMIT_OUT       directory for the manifest, JSON and HTML report (default: $RUNNER_TEMP/skimit or ./skimit-out)
set -euo pipefail

base_sha="${1:?usage: action/run.sh <base-sha>}"
head_dir="$(pwd)"
out="${SKIMIT_OUT:-${RUNNER_TEMP:-$head_dir}/skimit-out}"
mkdir -p "$out"
base_dir="$out/base"
summary="${GITHUB_STEP_SUMMARY:-/dev/null}"

log() { echo "skimit: $*" >&2; }

# The job only reports: anything that stops the comparison is written to the summary and shown as
# a warning, and the step still succeeds. Only fail-on-must-review fails it.
give_up() {
  log "$1"
  echo "::warning title=skimit::$1"
  printf '## skimit\n\n%s\n' "$1" >> "$summary"
  exit 0
}

# The jar: a path, a release, or the latest release.
version="${SKIMIT_VERSION:-latest}"
if [ -f "$version" ]; then
  jar="$version"
else
  jar="$out/skimit.jar"
  if [ "$version" = latest ]; then url="https://github.com/PeterToulouras/skimit/releases/latest/download/skimit.jar"
  else url="https://github.com/PeterToulouras/skimit/releases/download/v${version#v}/skimit.jar"; fi
  log "downloading $url"
  curl -fsSL --retry 3 -o "$jar" "$url" || give_up "Could not download skimit from $url."
fi

build() {
  if [ -n "${SKIMIT_BUILD:-}" ]; then bash -c "$SKIMIT_BUILD"
  # The base is a git worktree, which some plugins (git-commit-id) cannot read; style checks are
  # skipped because only the classes matter here.
  elif [ -f pom.xml ]; then mvn -q -B -DskipTests -Dcheckstyle.skip=true -Denforcer.skip=true \
      -Dmaven.gitcommitid.skip=true -Dspring-javaformat.skip=true -Dlicense.skip=true \
      -Dformatter.skip=true -Dimpsort.skip=true -Drewrite.skip=true compile
  elif [ -x ./gradlew ]; then ./gradlew -q classes
  elif [ -f build.gradle ] || [ -f build.gradle.kts ]; then gradle -q classes
  else log "no pom.xml or Gradle build found; set the build input"; return 1
  fi
}

# Compiled class folders under a directory; the second argument is a path to leave out.
classes() {
  find "$1" -type d \( -path '*/target/classes' -o -path '*/build/classes/java/main' \) \
    -not -path '*/node_modules/*' -not -path "${2:-/nonexistent}/*" | sort
}

# The base: the same repository at the pull request's base commit, in a worktree beside it.
git -C "$head_dir" fetch -q --no-tags --depth=1 origin "$base_sha" 2>/dev/null || true
rm -rf "$base_dir"
git -C "$head_dir" worktree add -q --detach "$base_dir" "$base_sha" \
  || give_up "The base commit \`$base_sha\` could not be checked out, so this change could not be compared."
trap 'git -C "$head_dir" worktree remove --force "$base_dir" 2>/dev/null || true' EXIT

log "building the head"
(cd "$head_dir" && build) \
  || give_up "This pull request does not build, so skimit could not compare it. The project's own build job shows the error."
log "building the base ($base_sha)"
(cd "$base_dir" && build) \
  || give_up "The base commit \`$base_sha\` does not build, so this change could not be compared."

# Read line by line rather than with mapfile, which the bash on macOS runners lacks.
head_classes=(); while IFS= read -r d; do head_classes+=("$d"); done < <(classes "$head_dir" "$out")
base_classes=(); while IFS= read -r d; do base_classes+=("$d"); done < <(classes "$base_dir")
if [ ${#head_classes[@]} -eq 0 ] || [ ${#base_classes[@]} -eq 0 ]; then
  give_up "No compiled classes were found after the build. Set the build input to a command that compiles the project."
fi

args=(diff --base "${base_classes[@]}" --head "${head_classes[@]}" --json "$out/manifest.json" --html "$out/report.html")
# The source diff lets the report show each changed method's own lines; verdicts never depend on it.
# Releases before 0.1.8 do not know --diff, and a workflow may pin one while using a newer Action.
if java -jar "$jar" --help 2>/dev/null | grep -q -- '--diff FILE' \
    && git -C "$head_dir" diff --no-color --no-ext-diff "$base_sha" HEAD -- . > "$out/changes.diff" 2>/dev/null; then
  args+=(--diff "$out/changes.diff")
fi
[ -n "${SKIMIT_POLICY:-}" ] && args+=(--policy "$head_dir/$SKIMIT_POLICY")
java -jar "$jar" "${args[@]}" > "$out/manifest.md" || give_up "The analysis did not finish; the step log shows why."

cat "$out/manifest.md" >> "$summary"
printf '\nThe interactive report is attached to this run as the `skimit-report` artifact.\n' >> "$summary"

must="$(python3 -c 'import json,sys; d=json.load(open(sys.argv[1])); print(d["mustReview"] + (1 if d["policy"] else 0))' "$out/manifest.json")"
{ echo "must-review=$must"; echo "report=$out/report.html"; } >> "${GITHUB_OUTPUT:-/dev/null}"

# One comment per pull request, updated on each push rather than repeated.
post_comment() {
  local body="$out/comment.md" existing
  { echo '<!-- skimit -->'; head -c 60000 "$out/manifest.md"; } > "$body"
  existing="$(gh api "repos/$GITHUB_REPOSITORY/issues/$PR_NUMBER/comments" --paginate \
    --jq '.[] | select(.body | startswith("<!-- skimit -->")) | .id' | tail -n 1)" || return 1
  if [ -n "$existing" ]; then
    gh api -X PATCH "repos/$GITHUB_REPOSITORY/issues/comments/$existing" -F "body=@$body" > /dev/null
  else
    gh pr comment "$PR_NUMBER" --body-file "$body"
  fi
}
if [ "${SKIMIT_COMMENT:-false}" = true ] && [ -n "${PR_NUMBER:-}" ]; then
  # A pull request from a fork gets a read-only token, so the comment can fail; the summary stands.
  post_comment || echo "::warning title=skimit::Could not post the pull request comment; the manifest is in the job summary."
fi

if [ "${SKIMIT_FAIL:-false}" = true ] && [ "$must" -gt 0 ]; then
  log "$must method(s) must be reviewed"
  exit 1
fi
