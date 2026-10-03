#!/usr/bin/env bash
# Builds the distributable llm4j-workflow-guide skill from the in-repo skill and docs/guide, so there is one source of truth.
#   scripts/package-skill.sh            -> dist/llm4j-workflow-guide/ and dist/llm4j-workflow-guide.zip
# The bundle is self-contained: SKILL.md plus references/ (the chapters). Paths in SKILL.md are rewritten to references/.
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=.claude/skills/llm4j-workflow-guide/SKILL.md
OUT=dist/llm4j-workflow-guide
rm -rf "$OUT" "$OUT.zip"; mkdir -p "$OUT/references"
cp docs/guide/*.md "$OUT/references/"
# links that leave the guide point at the repository, which a standalone skill does not have: make them absolute GitHub links
REPO="${LLM4J_REPO_URL:-https://github.com/srijithunni7182/llm4j/blob/main}"
for f in "$OUT"/references/*.md; do
  sed -E "s#\]\((\.\./\.\./|\.\./)([^)#]*)#](${REPO}/\2#g" "$f" > "$f.tmp"
  # links between chapters stay relative (they were written as NN-name.md or README.md)
  mv "$f.tmp" "$f"
done
sed -E 's#`docs/guide/#`references/#g' "$SRC" > "$OUT/SKILL.md"
sed -i -E "s#\`(eval4j/docs/[A-Z-]+\.md)\`#\`\1\` (in the llm4j repository: ${REPO}/\1)#" "$OUT/SKILL.md"
( cd dist && zip -qr llm4j-workflow-guide.zip llm4j-workflow-guide )
echo "built $OUT and $OUT.zip ($(ls "$OUT/references" | wc -l) chapters)"
