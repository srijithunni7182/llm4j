#!/usr/bin/env bash
# Builds the distributable skills: llm4j-workflow-guide (below) and llm4j-loom-deployer (at the end of this script).
# The workflow skill: a folder (and zip) that works anywhere, with no repository and no separate install.
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
sed -i -E 's#in this repo it is `[^`]*`( \(and `[^`]*` for running it, watched\))?, and `[^`]*` builds a standalone bundle \([^)]*\) from these chapters for distribution\.#this folder is that skill (the llm4j-loom-deployer skill, for running it watched, is a separate folder).#' "$OUT/references/README.md"

# Tell the agent where weave is: wherever the guide says `weave`, it means this launcher unless weave is already on the PATH.
note='**`weave` is bundled with this skill.** If `weave` is not on the PATH, run `scripts/weave` (Windows: `scripts\\weave.cmd`) from this skill'"'"'s folder wherever a step says `weave`; it runs `bin/weave.jar` and needs Java 17 or newer (`java -version`). Run it from the user'"'"'s project folder, with the launcher'"'"'s full path.'
awk -v n="$note" '{print} /Everything you need is in the tools:$/ {print ""; print "- " n}' "$OUT/SKILL.md" > "$OUT/SKILL.md.tmp"
mv "$OUT/SKILL.md.tmp" "$OUT/SKILL.md"
grep -q "bundled with this skill" "$OUT/SKILL.md" || { echo "could not add the bundled-weave note to SKILL.md" >&2; exit 1; }

( cd "$root/dist" && zip -qr llm4j-workflow-guide.zip llm4j-workflow-guide )
echo "built $OUT and $OUT.zip ($(du -h "$OUT.zip" | cut -f1), $(ls "$OUT/references" | wc -l) reference files)"

# ---------------------------------------------------------------------------------------------------------------------------------------
# The deployer skill: SKILL.md, references/ (its chapters), scripts/ (the launch scripts) from the weave jar itself, and bin/ with the jars the scripts launch:
# weave.jar, loom-inspect.jar, loom-inspect-core.jar (the add-on) and fabric.jar. Java 17 or newer is the only prerequisite.
DEP="$root/dist/llm4j-loom-deployer"
rm -rf "$DEP" "$DEP.zip" "$root/dist/.stage"; mkdir -p "$root/dist/.stage"
( cd "$root/dist/.stage" && java -jar "$jar" guide --install-skill --skill deployer >/dev/null )
mv "$root/dist/.stage/.claude/skills/llm4j-loom-deployer" "$DEP"
rm -rf "$root/dist/.stage"
pick() { ls "$root"/src/loom/$1/target/$2 2>/dev/null | grep -v -E 'sources|javadoc|tests' | head -n 1; }
fabric="$(pick loom-fabric 'loom-fabric-*-all.jar')"; inspect="$(pick loom-inspect 'loom-inspect-[0-9]*.jar')"; core="$(pick loom-inspect-core 'loom-inspect-core-[0-9]*.jar')"
[ -f "$fabric" ] && [ -f "$inspect" ] && [ -f "$core" ] || { echo "the add-on or Fabric jars are not built: mvn -DskipTests -pl src/loom/loom-fabric,src/loom/loom-inspect -am package" >&2; exit 2; }
mkdir -p "$DEP/bin"
cp "$jar" "$DEP/bin/weave.jar"; cp "$fabric" "$DEP/bin/fabric.jar"; cp "$inspect" "$DEP/bin/loom-inspect.jar"; cp "$core" "$DEP/bin/loom-inspect-core.jar"
chmod +x "$DEP"/scripts/*.sh
for f in "$DEP"/references/*.md; do
  sed -E "s#\]\((\.\./)+([^)#]*)#](${REPO}/\2#g" "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done
( cd "$root/dist" && zip -qr llm4j-loom-deployer.zip llm4j-loom-deployer )
echo "built $DEP and $DEP.zip ($(du -h "$DEP.zip" | cut -f1), $(ls "$DEP/references" | wc -l) chapters, $(ls "$DEP/scripts" | wc -l) scripts)"

# ---------------------------------------------------------------------------------------------------------------------------------------
# The harness-design skill: text only (SKILL.md and references/), straight from the weave jar, so it needs no jar of its own.
HAR="$root/dist/llm4j-harness-design"
rm -rf "$HAR" "$HAR.zip" "$root/dist/.stage"; mkdir -p "$root/dist/.stage"
( cd "$root/dist/.stage" && java -jar "$jar" guide --install-skill --skill harness >/dev/null )
mv "$root/dist/.stage/.claude/skills/llm4j-harness-design" "$HAR"
rm -rf "$root/dist/.stage"
( cd "$root/dist" && zip -qr llm4j-harness-design.zip llm4j-harness-design )
echo "built $HAR and $HAR.zip ($(du -h "$HAR.zip" | cut -f1), $(ls "$HAR/references" | wc -l) reference files)"
