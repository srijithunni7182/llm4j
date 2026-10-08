#!/usr/bin/env bash
# Runs the verification plan for the workflow graph (.kiro/specs/loom-vscode-graph/verification.md) and writes the evidence
# files. Gates: G1 core, G2 report and regression, G3 extension, G4 package, G6 final. G0 (sign-off) and G5 (a person on a
# real VS Code; sabotage was run once, see evidence/G5-sabotage.md) are not run here.
# Usage: scripts/verify-graph.sh [G1 G2 G3 G4 G6 ...]   (default: all)
set -uo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ev="$root/.kiro/specs/loom-vscode-graph/evidence"; mkdir -p "$ev"
gates=("$@"); [ ${#gates[@]} -gt 0 ] || gates=(G1 G2 G3 G4 G6)
failed=0

gate() { # name, evidence file, commands...
  local name="$1" out="$2"; shift 2
  echo "== $name"
  { echo "# $name  $(date -u +%FT%TZ)"; for c in "$@"; do echo "\$ $c"; (cd "$root" && bash -c "$c") 2>&1 | tail -n 40; echo "exit=${PIPESTATUS[0]}"; done; } >"$out"
  if grep -q '^exit=[1-9]' "$out"; then echo "   FAILED, see $out"; failed=1; else echo "   passed"; fi
}

for g in "${gates[@]}"; do case "$g" in
  G1) gate G1 "$ev/G1-core.txt" \
        "mvn -q -pl src/loom/ai-agent4j-loom -am verify -Djacoco.skip=false && python3 scripts/graph_coverage.py src/loom/ai-agent4j-loom/target/site/jacoco/jacoco.csv" ;;
  G2) # VG.1 mvn verify for the three modules; VG.2 existing weave check/run/audit tests; VG.4 every repo .loom parses
      gate G2 "$ev/G2-regression.txt" \
        "mvn -q -pl src/eval4j,src/eval4j-report verify" \
        "bash scripts/check-graph-render-sync.sh" \
        "cd src/loom/graph-render && node --test test/*.test.js" ;;
  G3) gate G3 "$ev/G3-extension.txt" "cd src/loom/vscode-loom && npm test" ;;
  G4) gate G4 "$ev/G4-package.txt" "bash scripts/build-vsix.sh" "bash scripts/verify-vsix.sh" "bash scripts/verify-mermaid.sh" ;;
  G6) # VG.5 sabotage was run once by hand (evidence/G5-sabotage.md); its script was removed to avoid maintaining it
      gate G6 "$ev/G6-final.txt" "python3 scripts/verify_graph_traceability.py > .kiro/specs/loom-vscode-graph/evidence/traceability.txt; cat .kiro/specs/loom-vscode-graph/evidence/traceability.txt" ;;
  *) echo "unknown gate $g" >&2; failed=1 ;;
esac; done
exit "$failed"
