#!/usr/bin/env python3
"""Gate G8: coverage of the generic tools from the JaCoCo CSV (line coverage of the package, branch coverage of the guards)."""
import csv
import sys

from loomgen import TOOLS

PACKAGE = "io.github.llm4j.tools"
GUARDS = ["NetPolicy", "SqlGuard", "RequestPath", "Redactor", "EffectTool", "PathGuard", "MailAddress"]
rows = [r for r in csv.DictReader(open(TOOLS / "target/site/jacoco/jacoco.csv")) if r["PACKAGE"] == PACKAGE]


def ratio(missed, covered):
    total = missed + covered
    return 1.0 if total == 0 else covered / total


lines_missed = sum(int(r["LINE_MISSED"]) for r in rows)
lines_covered = sum(int(r["LINE_COVERED"]) for r in rows)
line_ratio = ratio(lines_missed, lines_covered)
print(f"{PACKAGE}: lines {lines_covered}/{lines_covered + lines_missed} = {line_ratio:.1%}  (target 80%)")
ok = line_ratio >= 0.80

print("\nper class (lines | branches):")
for r in sorted(rows, key=lambda r: r["CLASS"]):
    lm, lc, bm, bc = (int(r[k]) for k in ("LINE_MISSED", "LINE_COVERED", "BRANCH_MISSED", "BRANCH_COVERED"))
    mark = ""
    if r["CLASS"] in GUARDS:
        b = ratio(bm, bc)
        mark = f"  <- guard, branches {b:.1%} (target 90%)"
        ok &= b >= 0.90
    print(f"  {r['CLASS']:<28} lines {lc:>4}/{lc + lm:<4} ({ratio(lm, lc):6.1%})  branches {bc:>3}/{bc + bm:<3} ({ratio(bm, bc):6.1%}){mark}")
missing = [g for g in GUARDS if g not in {r["CLASS"] for r in rows}]
if missing:
    print("guard classes not found in the report:", missing)
    ok = False
print("\nG8 coverage:", "OK" if ok else "BELOW TARGET")
sys.exit(0 if ok else 1)
