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
"$weave" check "$work/project/app/main.loom" --no-env >/dev/null 2>&1
"$weave" eval "$work/project/app/main.loom" --mock >/dev/null 2>&1
echo "ok: the skill package works from an empty folder with its bundled weave"
