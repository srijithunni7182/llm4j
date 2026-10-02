#!/usr/bin/env python3
"""Gate G3 for rewind and fork: the tests can fail. Breaks one rule at a time in the real sources, runs the rewind tests, and checks that tests
tagged with the expected checks fail. Sources are restored afterwards, whatever happens.

    scripts/sabotage_rewind.py [--only M1,M7]      writes .kiro/specs/loom-rewind-and-fork/evidence/G3-sabotage.md
"""
import argparse
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / "loom" / "ai-agent4j-loom"
REPORTS = MODULE / "target" / "surefire-reports"
SPEC = ROOT / ".kiro" / "specs" / "loom-rewind-and-fork"
L = "loom/ai-agent4j-loom/src/main/java/io/github/llm4j/loom/"
TESTS = "io.github.llm4j.loom.rewind.*Test,TravelCommandsTest,RewindGuideTest"

SABOTAGES = [
    ("M1", "Key effects with the generation (an identical send in a new attempt is repeated)",
     [(L + "runtime/Generations.java", 'if (boundaries.stream().noneMatch(b -> "repeat".equals(b.effects()))) return strip(step);', "if (true) return step;")], ["V4.1"]),
    ("M2", "Key a model call without its generation (the new attempt finds the old answer)",
     [(L + "execution/HarnessExecutor.java", 'step.set(block + i + (generation > 1 ? "~" + generation : ""));', "step.set(block + i);")], ["V3.1"]),
    ("M3", "Overwrite old entries when rewinding",
     [(L + "runtime/Generations.java", "        boundaries.add(b);\n        return b;",
       '        boundaries.add(b);\n        for (String k : new ArrayList<>(journal.all().keySet())) if (!k.startsWith("#") && k.startsWith(block)) journal.put(k, new RunJournal.Entry("discarded", ""));\n        return b;')], ["V3.1"]),
    ("M4", "Skip the boundary write",
     [(L + "runtime/Generations.java", 'journal.put(KEY, new RunJournal.Entry("boundaries", value));', "")], ["V3.3"]),
    ("M5", "Write the boundary late: the first rewind is only persisted when a second one happens",
     [(L + "runtime/Generations.java", 'journal.put(KEY, new RunJournal.Entry("boundaries", value));', 'if (!boundaries.isEmpty()) journal.put(KEY, new RunJournal.Entry("boundaries", value));')], ["V3.3"]),
    ("M6", "Ignore ask first (a rewind goes ahead over performed effects)",
     [(L + "execution/Rewinder.java", "if (!blockers.isEmpty() && policy == RewindStmt.Effects.ASK_FIRST) {", "if (false) {")], ["V4.3"]),
    ("M7", "Let a fork write to its parent",
     [(L + "travel/OverlayJournal.java", "        layer.put(stepId, entry);", "        below.put(stepId, entry);")], ["V5.10"]),
    ("M8", "Take reset --failed back to the start",
     [(L + "travel/RunTravel.java", "        if (failedOnly) {", "        if (false) {")], ["V5.8"]),
    ("M9", "Let the overall rewind cap default to unbounded",
     [(L + "execution/Rewinder.java", "DEFAULT_MAX_REWINDS = 20;", "DEFAULT_MAX_REWINDS = Integer.MAX_VALUE;")], ["V2.4"]),
    ("M10", "Treat an effect with an unknown outcome as not blocking",
     [(L + "runtime/EffectScan.java", 'if (!"effect_done".equals(kind) && !"effect_pending".equals(kind)) continue;', 'if (!"effect_done".equals(kind)) continue;')], ["V4.4"]),
    ("M11", "Drop the spend of replaced attempts from the budget on resume",
     [(L + "execution/HarnessExecutor.java", '            Map<String, Object> u = castMap(raw);\n            io.github.llm4j.budget.Charge charge',
       '            if (!generations().isCurrent(e.getKey().substring(0, Math.max(0, e.getKey().indexOf(USAGE))))) continue;\n            Map<String, Object> u = castMap(raw);\n            io.github.llm4j.budget.Charge charge')], ["V6.1"]),
    ("M12", "Let a simulated run perform the effects of tools that are not known to be effect-safe",
     [(L + "execution/SimulatingTool.java", "        return SIMULATED;", "        try { return real.execute(args); } catch (Exception e) { throw new RuntimeException(e); }")], ["V4.8"]),
]


def failing():
    out = set()
    for xml in REPORTS.glob("TEST-*.xml"):
        for case in ET.parse(xml).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                out.add((case.get("classname"), re.sub(r"\(.*$|\[.*$", "", case.get("name"))))
    return out


def tags():
    out = {}
    for f in (MODULE / "src/test/java").rglob("*.java"):
        src = f.read_text()
        if 'Tag("RW-' not in src:
            continue
        pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
        for m in re.finditer(r"((?:\s*@[\w.]+(?:\([^)]*\))?)+)\s*(?:public |private |protected )?(?:static )?void (\w+)\(", src):
            found = set(re.findall(r'@Tag\("RW-(V\d+\.\d+)"\)', m.group(1)))
            if found:
                out[(f"{pkg}.{f.stem}", m.group(2))] = found
    return out


def run_one(edits, expected):
    originals = {}
    try:
        for path, old, new in edits:
            f = ROOT / path
            originals.setdefault(f, f.read_text())
            text = f.read_text()
            if text.count(old) != 1:
                return "NOT APPLIED", f"expected exactly one match for {old[:60]!r} in {path}, found {text.count(old)}", []
            f.write_text(text.replace(old, new))
        shutil.rmtree(REPORTS, ignore_errors=True)
        proc = subprocess.run(["mvn", "-B", "-o", "-q", "test", f"-Dtest={TESTS}", "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false", "-Djacoco.skip=true"],
                              cwd=MODULE, capture_output=True, text=True, timeout=1500)
        out = proc.stdout + proc.stderr
        if "COMPILATION ERROR" in out:
            return "COMPILE ERROR", " ".join(l for l in out.splitlines() if "ERROR" in l and ".java" in l)[:300], []
        if not list(REPORTS.glob("TEST-*.xml")):
            return "NO REPORT", out[-400:], []
        known = tags()
        bad = failing()
        failed_tags = sorted({t for k in bad for t in known.get(k, set())})
        names = sorted(f"{c.split('.')[-1]}.{m}" for c, m in bad)
        if not bad:
            return "SURVIVED", "no test failed", []
        missing = [t for t in expected if t not in failed_tags]
        return ("DETECTED" if not missing else "WEAK"), f"failing checks {failed_tags}" + (f"; expected {missing} did not fail" if missing else ""), names
    finally:
        for f, text in originals.items():
            f.write_text(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    only = {s for s in ap.parse_args().only.split(",") if s}
    rows = []
    for sid, description, edits, expected in SABOTAGES:
        if only and sid not in only:
            continue
        print(f"{sid}: {description} ...", flush=True)
        status, detail, names = run_one(edits, expected)
        print(f"   -> {status}: {detail}", flush=True)
        rows.append((sid, description, expected, status, detail, names))
    out = ["# G3: sabotage runs", "", "Each row breaks one rule in the real source, runs the rewind and fork tests, and restores the source.",
           "**DETECTED** means every expected check failed.", "",
           "| # | Sabotage | Expected to fail | Result | Failing checks | Failing tests |", "|---|---|---|---|---|---|"]
    for sid, desc, expected, status, detail, names in rows:
        shown = ", ".join(names[:6]) + (f" (+{len(names) - 6} more)" if len(names) > 6 else "")
        out.append(f"| {sid} | {desc} | {', '.join(expected)} | **{status}** | {detail} | {shown} |")
    evidence = SPEC / "evidence"
    evidence.mkdir(exist_ok=True)
    (evidence / ("G3-sabotage.md" if not only else "G3-sabotage-partial.md")).write_text("\n".join(out) + "\n")
    bad = [r for r in rows if r[3] != "DETECTED"]
    print(f"\n{len(rows) - len(bad)}/{len(rows)} detected" + ("" if not bad else "; NOT detected: " + ", ".join(f"{r[0]} ({r[3]})" for r in bad)))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
