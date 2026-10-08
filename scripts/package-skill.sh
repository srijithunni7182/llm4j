#!/usr/bin/env bash
# Builds the distributable llm4j-workflow-guide skill: a folder (and zip) that works anywhere, with no repository and no separate install.
#   scripts/package-skill.sh [path/to/weave-cli.jar]  -> dist/llm4j-workflow-guide/ and dist/llm4j-workflow-guide.zip
# Contents: SKILL.md, references/ (the chapters, the Loom reference, recipes), bin/weave.jar (the runnable weave jar) and
# scripts/weave, scripts/weave.cmd (launchers that run the bundled jar; Java 17 or newer is the only prerequisite).
# The skill and references come from the jar itself (weave guide --install-skill), so the jar and the skill can never disagree.
set -euo pipefail
# a jar path given relative to where you are must be resolved before this script moves to the repository root
given="${1:-}"
[ -z "$given" ] || given="$(cd "$(dirname "$given")" 2>/dev/null && pwd)/$(basename "$given")"
cd "$(dirname "$0")/.."
root="$PWD"
jar="${given:-$(ls "$root"/src/loom/ai-agent4j-loom/target/ai-agent4j-loom-*-cli.jar 2>/dev/null | head -n 1)}"
[ -f "$jar" ] || { echo "no weave jar: build it first (mvn -DskipTests package) or pass its path" >&2; exit 2; }
OUT="$root/dist/llm4j-workflow-guide"
rm -rf "$OUT" "$OUT.zip" "$root/dist/.stage"; mkdir -p "$root/dist/.stage"
( cd "$root/dist/.stage" && java -jar "$jar" guide --install-skill >/dev/null )
mv "$root/dist/.stage/.claude/skills/llm4j-workflow-guide" "$OUT"
rm -rf "$root/dist/.stage"

mkdir -p "$OUT/bin" "$OUT/scripts"
cp "$jar" "$OUT/bin/weave.jar"
cat > "$OUT/scripts/weave" <<'S'
#!/usr/bin/env sh
# Runs the weave jar bundled with this skill. Needs Java 17 or newer.
here="$(cd "$(dirname "$0")" && pwd)"
exec java -jar "$here/../bin/weave.jar" "$@"
S
chmod +x "$OUT/scripts/weave"
cat > "$OUT/scripts/weave.cmd" <<'S'
@echo off
java -jar "%~dp0..\bin\weave.jar" %*
S

# Links that leave the guide point into the repository, which a standalone skill does not have: make them absolute GitHub links.
REPO="${LLM4J_REPO_URL:-https://github.com/srijithunni7182/llm4j/blob/main}"
for f in "$OUT"/references/*.md; do
  sed -E "s#\]\((\.\./)+([^)#]*)#](${REPO}/\2#g" "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done
# the guide's README tells repository readers where the skill lives; for a reader of the skill, this folder is it
sed -i -E 's#in this repo it is `[^`]*`, and `[^`]*` builds a standalone bundle \([^)]*\) from these chapters for distribution\.#this folder is that skill.#' "$OUT/references/README.md"

# Tell the agent where weave is: wherever the guide says `weave`, it means this launcher unless weave is already on the PATH.
note='**`weave` is bundled with this skill.** If `weave` is not on the PATH, run `scripts/weave` (Windows: `scripts\\weave.cmd`) from this skill'"'"'s folder wherever a step says `weave`; it runs `bin/weave.jar` and needs Java 17 or newer (`java -version`). Run it from the user'"'"'s project folder, with the launcher'"'"'s full path.'
awk -v n="$note" '{print} /Everything you need is in the tools:$/ {print ""; print "- " n}' "$OUT/SKILL.md" > "$OUT/SKILL.md.tmp"
mv "$OUT/SKILL.md.tmp" "$OUT/SKILL.md"
grep -q "bundled with this skill" "$OUT/SKILL.md" || { echo "could not add the bundled-weave note to SKILL.md" >&2; exit 1; }

( cd "$root/dist" && zip -qr llm4j-workflow-guide.zip llm4j-workflow-guide )
echo "built $OUT and $OUT.zip ($(du -h "$OUT.zip" | cut -f1), $(ls "$OUT/references" | wc -l) reference files)"
