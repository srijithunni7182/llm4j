#!/usr/bin/env bash
# Proves the packaged skill works on its own: unzip it in an empty folder, away from the repository, and use only what is inside it.
# Usage: scripts/verify-skill-package.sh [dist/llm4j-workflow-guide.zip]
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
zip="${1:-$root/dist/llm4j-workflow-guide.zip}"
[ -f "$zip" ] || { echo "no package: run scripts/package-skill.sh" >&2; exit 2; }
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
mkdir -p "$work/skills" "$work/project"
unzip -q "$zip" -d "$work/skills"
skill="$work/skills/llm4j-workflow-guide"
for f in SKILL.md bin/weave.jar scripts/weave scripts/weave.cmd references/LOOM_GUIDE.md references/RECIPES.md references/01-decide-your-agents.md; do
  [ -e "$skill/$f" ] || { echo "missing from the package: $f" >&2; exit 1; }
done
[ -x "$skill/scripts/weave" ] || { echo "scripts/weave is not executable" >&2; exit 1; }
# a path into the repository is fine only as an absolute https link; a relative one (or a script of the repository) would not exist here
pattern='docs/guide|\]\(\.\./|scripts/(build|verify|package)-'
if grep -rEn "$pattern" "$skill/SKILL.md" "$skill/references" | grep -v "https://" >/dev/null; then
  grep -rEn "$pattern" "$skill/SKILL.md" "$skill/references" | grep -v "https://" | head >&2
  echo "the skill points at a repository path" >&2; exit 1
fi
grep -q "bundled with this skill" "$skill/SKILL.md" || { echo "SKILL.md does not say where weave is" >&2; exit 1; }
cd "$work/project"
weave="$skill/scripts/weave"
"$weave" --version >/dev/null
"$weave" guide >/dev/null
"$weave" init pipeline "$work/project/app" >/dev/null
script="$work/project/app/src/main/resources/main.loom"
[ -f "$script" ] || { echo "weave init did not make src/main/resources/main.loom" >&2; exit 1; }
"$weave" check "$script" --no-env >/dev/null 2>&1 || { echo "weave check failed on the starter" >&2; exit 1; }
"$weave" eval "$script" --mock >/dev/null 2>&1 || { echo "weave eval --mock failed on the starter" >&2; exit 1; }
echo "ok: the skill package works from an empty folder with its bundled weave"

# ---------------------------------------------------------------------------------------------------------------------------------------
# The deployer skill, from its own zip, away from the repository: its scripts bring up a real Fabric, a workflow reports to it, and everything is stopped.
dzip="${2:-$root/dist/llm4j-loom-deployer.zip}"
if [ -f "$dzip" ]; then
  dwork="$(mktemp -d)"; trap 'rm -rf "$work" "$dwork"' EXIT
  unzip -q "$dzip" -d "$dwork"
  dep="$dwork/llm4j-loom-deployer"
  for f in SKILL.md bin/weave.jar bin/fabric.jar bin/loom-inspect.jar bin/loom-inspect-core.jar scripts/fabric-up.sh scripts/run-observed.sh scripts/fabric-down.sh scripts/fabric-status.sh scripts/fabric-up.ps1 references/01-decide-the-shape.md references/08-operate-and-troubleshoot.md; do
    [ -e "$dep/$f" ] || { echo "missing from the deployer package: $f" >&2; exit 1; }
  done
  if grep -rEn 'docs/deploy|docs/guide|\]\(\.\./' "$dep/SKILL.md" "$dep/references" | grep -v "https://" >/dev/null; then
    echo "the deployer skill points at a repository path" >&2; exit 1
  fi
  mkdir -p "$dwork/app"; printf 'workflow Main() {\n    note "hello from the package check"\n}\n' > "$dwork/app/flow.loom"
  port="$(python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1])' 2>/dev/null || echo 19399)"
  trap '"$dep/scripts/fabric-down.sh" --data "$dwork/fd" >/dev/null 2>&1 || true; rm -rf "$work" "$dwork"' EXIT
  "$dep/scripts/fabric-up.sh" --data "$dwork/fd" --port "$port" --generate-password >/dev/null || { echo "fabric-up failed from the package" >&2; exit 1; }
  ( cd "$dwork/app" && "$dep/scripts/run-observed.sh" --script flow.loom --fabric "http://127.0.0.1:$port" --name pkgcheck --data "$dwork/fd" >/dev/null 2>&1 ) || { echo "run-observed failed from the package" >&2; exit 1; }
  status="$("$dep/scripts/fabric-status.sh" --data "$dwork/fd" --port "$port")"
  case "$status" in running*) ;; *) echo "fabric-status does not say running: $status" >&2; exit 1 ;; esac
  echo "ok: the deployer package starts a Fabric, a workflow reports to it, and both stop, from an empty folder"
else
  echo "(no deployer package at $dzip: run scripts/package-skill.sh)"
fi

# ---------------------------------------------------------------------------------------------------------------------------------------
# The harness-design skill, from its own zip, away from the repository: text only, so it must be complete and point at no repository path.
hzip="${3:-$root/dist/llm4j-harness-design.zip}"
if [ -f "$hzip" ]; then
  hwork="$(mktemp -d)"; trap 'rm -rf "$hwork" "${work:-}" "${dwork:-}"' EXIT
  unzip -q "$hzip" -d "$hwork"
  har="$hwork/llm4j-harness-design"
  for f in SKILL.md references/principles.md references/failure-modes.md references/loom-mapping.md references/design-record.md; do
    [ -e "$har/$f" ] || { echo "missing from the harness-design package: $f" >&2; exit 1; }
  done
  grep -q '^name: llm4j-harness-design' "$har/SKILL.md" || { echo "the harness-design SKILL.md has no name" >&2; exit 1; }
  if grep -rEn 'docs/(guide|deploy)|\]\(\.\./' "$har/SKILL.md" "$har/references" | grep -v "https://" >/dev/null; then
    echo "the harness-design skill points at a repository path" >&2; exit 1
  fi
  echo "ok: the harness-design package is complete and stands alone"
else
  echo "(no harness-design package at $hzip: run scripts/package-skill.sh)"
fi
