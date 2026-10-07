#!/usr/bin/env bash
# One command from a clean checkout to "ready to try": build and test the core libraries, package the skill, build the VS Code extension and install it.
#
#   scripts/quick-build.sh [--no-install] [--skip-skill] [--skip-extension] [--dry-run]
#
# 1. mvn install of the six published libraries (ai-agent4j, addons, tools, eval4j, eval4j-report, Loom). The default build already leaves out every
#    test tagged live, integration or fragile, and the examples, engram and tantrik are not part of it. The libraries are installed into ~/.m2 so a
#    project made by `weave init` can build against them (its pom depends on them, and they are not on Maven Central yet).
# 2. the skill package: dist/llm4j-workflow-guide.zip (with the weave jar), checked from an empty folder.
# 3. the VS Code extension: loom/vscode-loom/vscode-loom-<version>.vsix (with the weave jar), installed with `code --install-extension` unless --no-install.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

install_ext=1 skill=1 ext=1 dry=0
for a in "$@"; do
  case "$a" in
    --no-install) install_ext=0 ;;
    --skip-skill) skill=0 ;;
    --skip-extension) ext=0 ;;
    --dry-run) dry=1 ;;
    -h|--help) sed -n 2,10p "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown option: $a (see --help)" >&2; exit 2 ;;
  esac
done

started=$SECONDS
step() { echo; echo "== $*"; }
run() { if [ "$dry" = 1 ]; then echo "   (dry run) $*"; else "$@"; fi; }

step "1/3 core libraries: build and test (live, integration and fragile tests are left out; no examples)"
run mvn -B install

if [ "$skill" = 1 ]; then
  step "2/3 the skill package"
  run scripts/package-skill.sh
  run scripts/verify-skill-package.sh
else
  step "2/3 the skill package (skipped)"
fi

if [ "$ext" = 1 ]; then
  step "3/3 the VS Code extension"
  run scripts/build-vsix.sh
  vsix="$(ls -t loom/vscode-loom/vscode-loom-*.vsix 2>/dev/null | head -n 1 || true)"
  if [ "$dry" = 1 ]; then
    echo "   (dry run) code --install-extension loom/vscode-loom/vscode-loom-<version>.vsix --force"
  elif [ "$install_ext" = 0 ]; then
    echo "   not installed (--no-install). To install: code --install-extension $vsix --force"
  elif ! command -v code >/dev/null 2>&1; then
    echo "   the 'code' command is not on the PATH, so it was not installed. In VS Code: Extensions, ..., Install from VSIX, and pick $vsix"
  else
    code --install-extension "$vsix" --force
    echo "   installed. Reload the VS Code window (Developer: Reload Window) to use it."
  fi
else
  step "3/3 the VS Code extension (skipped)"
fi

echo
echo "done in $((SECONDS - started)) s:"
[ "$dry" = 1 ] && exit 0
ls -1 dist/llm4j-workflow-guide.zip loom/vscode-loom/vscode-loom-*.vsix 2>/dev/null | sed 's/^/   /' || true
echo "   weave jar: $(ls loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar | head -n 1)"
