#!/usr/bin/env bash
# Builds the distributable llm4j-workflow-guide skill from the in-repo skill and eval4j/docs/guide, so there is one source of truth.
#   scripts/package-skill.sh            -> dist/llm4j-workflow-guide/ and dist/llm4j-workflow-guide.zip
# The bundle is self-contained: SKILL.md plus references/ (the chapters). Paths in SKILL.md are rewritten to references/, and
# every link that leaves the guide becomes an absolute link into the repository (set LLM4J_REPO_URL if it is hosted elsewhere).
set -euo pipefail
cd "$(dirname "$0")/.."
SRC=.claude/skills/llm4j-workflow-guide/SKILL.md
GUIDE=eval4j/docs/guide
OUT=dist/llm4j-workflow-guide
REPO="${LLM4J_REPO_URL:-https://github.com/srijithunni7182/llm4j/blob/main}"
rm -rf "$OUT" "$OUT.zip"; mkdir -p "$OUT/references"
cp "$GUIDE"/*.md "$OUT/references/"
python3 - "$OUT/references" "$GUIDE" "$REPO" <<'PY'
import os, re, sys, glob
out, guide, repo = sys.argv[1:4]
link = re.compile(r'\]\(([^)#\s]+)(#[^)\s]*)?\)')
for f in glob.glob(out + '/*.md'):
    text = open(f, encoding='utf-8').read()
    def fix(m):
        target, frag = m.group(1), m.group(2) or ''
        if re.match(r'^[a-z]+:', target):
            return m.group(0)
        resolved = os.path.normpath(os.path.join(guide, target))
        if resolved.startswith(guide + os.sep):          # a link between chapters stays relative
            return '](' + os.path.basename(resolved) + frag + ')'
        return '](' + repo + '/' + resolved + frag + ')'
    open(f, 'w', encoding='utf-8').write(link.sub(fix, text))
PY
sed -E "s#\`eval4j/docs/guide/#\`references/#g" "$SRC" > "$OUT/SKILL.md"
sed -i -E "s#\`(eval4j/docs/[A-Z-]+\.md)\`#\`\1\` (in the llm4j repository: ${REPO}/\1)#" "$OUT/SKILL.md"
( cd dist && zip -qr llm4j-workflow-guide.zip llm4j-workflow-guide )
echo "built $OUT and $OUT.zip ($(ls "$OUT/references" | wc -l) chapters)"
