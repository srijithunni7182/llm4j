#!/usr/bin/env bash
# Proves the kit stands alone: with ONLY the weave jar, in an empty folder, with no repository, no keys and no network use, a person can
#   start from each template, check it, evaluate it with the mock model, read every guide page, and install the skill.
# Fails if any of that needs a file from the repository. Usage: scripts/verify-kit.sh [path/to/weave.jar]
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
jar="${1:-$(ls "$root"/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar | head -n 1)}"
[ -f "$jar" ] || { echo "no weave jar: build it with mvn -DskipTests -Djacoco.skip=true package"; exit 2; }
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$work/kit" "$work/empty"
cp "$jar" "$work/kit/weave.jar"
cd "$work/empty"
# No keys, no repository: a clean environment with only what a newcomer has.
weave() { env -i PATH="$PATH" HOME="$work/empty" java -jar "$work/kit/weave.jar" "$@"; }
fail() { echo "FAIL: $*"; exit 1; }

echo "== every template: init, check --no-env, eval --check, eval --mock, audit, explain, next"
for t in pipeline approval classifier; do
  weave init "$t" "$t-project" >/dev/null || fail "init $t"
  [ -f "$t-project/.env.example" ] || fail "$t has no .env.example"
  grep -qx '.env' "$t-project/.gitignore" || fail "$t does not ignore .env"
  script="$t-project/src/main/resources/main.loom"
  [ -f "$script" ] || fail "$t has no src/main/resources/main.loom"
  [ -f "$t-project/pom.xml" ] && [ -f "$t-project/src/test/resources/eval/golden/workflow.yaml" ] || fail "$t is not a Maven project with a golden dataset under src/test"
  weave check "$script" --no-env >/dev/null || fail "check $t"
  weave eval "$script" --check >/dev/null || fail "eval --check $t"
  weave eval "$script" --mock >/dev/null || fail "eval --mock $t"
  weave audit "$script" --fail-on high >/dev/null || fail "audit $t"
  weave explain "$script" >/dev/null || fail "explain $t"
  weave next "$t-project" >/dev/null || fail "next $t"
  echo "   $t ok"
done

echo "== every guide page prints"
for p in readme 1 2 3 4 5 6 7 8 9 10 recipes loom llms; do
  [ "$(weave guide "$p" | wc -c)" -gt 200 ] || fail "weave guide $p is empty"
done
echo "   14 pages ok"

echo "== the skill installs, points only at what it installs, and names no repository path"
weave guide --install-skill "$work/empty/skill-project" >/dev/null || fail "install-skill"
skill="$work/empty/skill-project/.claude/skills/llm4j-workflow-guide"
[ -f "$skill/SKILL.md" ] || fail "no SKILL.md"
for ref in $(grep -o 'references/[0-9a-z-]*\.md' "$skill/SKILL.md" | sort -u); do [ -f "$skill/$ref" ] || fail "the skill names $ref and it was not installed"; done
if grep -nE 'docs/guide/|examples/|eval4j/src|loom/ai-agent4j|hexamind-hub|scripts/' "$skill/SKILL.md"; then fail "the installed skill names a repository path"; fi
echo "   skill ok"
echo "ok: the kit works from the jar alone"
