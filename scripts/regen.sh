#!/usr/bin/env bash
# Regenerates every file in the repository that is generated from something else, in one go:
#   docs/COMMAND_REFERENCE.md                    from the commands themselves   (scripts/make-command-reference.sh)
#   src/loom/starters/*/README.md pictures       from each starter's main.loom  (scripts/make-starter-diagrams.sh)
#   the graph renderer's copies and stylesheet   from graph-render.js           (scripts/sync-graph-render.sh)
#   docs/review/rules/                           from the review rules          (RuleDocsGeneratedTest, -Dregenerate.rule.docs=true)
#   the Loom tutorial's web page                 from docs/tutorial/*.md        (scripts/make-tutorial-html.sh)
#
# Usage: scripts/regen.sh                   regenerate everything
#        scripts/regen.sh --check           regenerate, then list every file that was out of date and exit 1 if there
#                                           were any. The files are left regenerated, ready to commit: this lists all the
#                                           stale ones at once, where the tests that pin them fail one at a time.
#        scripts/regen.sh --skip-tutorial   leave the tutorial page out (it needs Node 18+ and a Chromium, and is the slowest)
# Make targets: `make regen` and `make docs-check` (which also runs the documentation tests).
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

check=false
tutorial=true
for arg in "$@"; do
  case "$arg" in
    --check) check=true ;;
    --skip-tutorial) tutorial=false ;;
    -h|--help) sed -n '2,16p' "$0"; exit 0 ;;
    *) echo "regen: unknown option '$arg' (see --help)" >&2; exit 2 ;;
  esac
done

CORE=src/ai-agent4j
LOOM=src/loom/ai-agent4j-loom

# One line per generated file: its hash and its path. A file that does not exist yet is simply absent from the list.
fingerprint() {
  {
    echo docs/COMMAND_REFERENCE.md
    find docs/review/rules -type f 2>/dev/null
    ls src/loom/starters/*/README.md
    echo "$LOOM/src/main/resources/guide/loom-tutorial.html"
    echo src/loom/vscode-loom/media/graph-render.js
    echo src/loom/vscode-loom/media/graph-render.css
    echo src/eval4j-report/src/main/resources/io/github/llm4j/evalreport/render/graph-render.js
  } | sort | while read -r f; do [ -f "$f" ] && sha256sum "$f"; done || true
}

step() { echo "== $*"; }

before=$(fingerprint)

step "building the Loom classes (the commands and starters are read from them)"
mvn -q -B -pl "$CORE,$LOOM" -Djacoco.skip=true -DskipTests process-classes
# Third-party libraries only: our own classes come from target/classes, so what is generated always matches this working tree.
if [ ! -d "$LOOM/target/lib" ]; then
  mvn -q -B -pl "$LOOM" dependency:copy-dependencies -DoutputDirectory=target/lib -DincludeScope=runtime \
    -DexcludeGroupIds=io.github.srijithunni7182
fi
export WEAVE_CP="$PWD/$LOOM/target/classes:$PWD/$CORE/target/classes:$PWD/$LOOM/target/lib/*"

step "the command reference"
bash scripts/make-command-reference.sh
step "the pictures in the starters' READMEs"
bash scripts/make-starter-diagrams.sh > /dev/null
step "the graph renderer's copies"
bash scripts/sync-graph-render.sh > /dev/null
step "the review rules' pages"
mvn -q -B -pl "$CORE,$LOOM" -Djacoco.skip=true -Dregenerate.rule.docs=true -Dtest=RuleDocsGeneratedTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

if $tutorial; then
  if command -v node > /dev/null 2>&1; then
    step "the tutorial's web page"
    bash scripts/make-tutorial-html.sh | tail -1
  else
    echo "== the tutorial's web page: skipped (Node 18+ is not installed)" >&2
  fi
fi

after=$(fingerprint)
stale=$(diff <(echo "$before") <(echo "$after") | sed -n 's/^[<>] [0-9a-f]*  //p' | sort -u || true)
if [ -z "$stale" ]; then
  echo "Everything generated is up to date."
elif $check; then
  echo
  echo "These generated files were out of date. They are regenerated now: review and commit them."
  echo "$stale" | sed 's/^/  /'
  exit 1
else
  echo
  echo "Regenerated; these files changed:"
  echo "$stale" | sed 's/^/  /'
fi
