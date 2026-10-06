#!/usr/bin/env python3
"""Prints JaCoCo line and branch coverage of the loom graph package from a jacoco.csv and fails below the plan's bar (V9.3)."""
import csv, sys
LINE_MIN, BRANCH_MIN = 0.90, 0.80
rows = [r for r in csv.DictReader(open(sys.argv[1])) if r["PACKAGE"].endswith(".loom.graph")]
if not rows:
    sys.exit("no rows for the graph package in " + sys.argv[1])
def ratio(kind):
    covered = sum(int(r[kind + "_COVERED"]) for r in rows); missed = sum(int(r[kind + "_MISSED"]) for r in rows)
    return covered / max(1, covered + missed), covered, covered + missed
line, branch = ratio("LINE"), ratio("BRANCH")
print(f"graph package: lines {line[0]:.1%} ({line[1]}/{line[2]}), branches {branch[0]:.1%} ({branch[1]}/{branch[2]})")
sys.exit(0 if line[0] >= LINE_MIN and branch[0] >= BRANCH_MIN else 1)
