#!/usr/bin/env python3
"""Gate G3 for earned autonomy: the tests can fail. Breaks one rule at a time in the real sources, runs the autonomy tests, and checks that tests
tagged with the expected checks fail. Sources are restored afterwards, whatever happens.

    scripts/sabotage_autonomy.py [--only M1,M7]      writes .kiro/specs/loom-earned-autonomy/evidence/G3-sabotage.md
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
SPEC = ROOT / ".kiro" / "specs" / "loom-earned-autonomy"
L = "loom/ai-agent4j-loom/src/main/java/io/github/llm4j/loom/"
TESTS = ("io.github.llm4j.loom.autonomy.*Test,AutonomyCommandsTest,ReplayCommandTest,AutonomyCompatTest,AutonomySweepTest,!ReplayScaleTest,!LadderPropertyTest")

SABOTAGES = [
    ("M1", "Show the proposal to the person in watch",
     [(L + "execution/Decider.java", "verdict = ask(def, step, fields, shown ? proposal : null);", "verdict = ask(def, step, fields, proposal);")], ["V4.1"]),
    ("M2", "Count cases decided with the proposal in view as evidence",
     [(L + "autonomy/Case.java", "!superseded && verdict != null && proposal != null && !shown && decider != null", "!superseded && verdict != null && proposal != null && decider != null")], ["V4.2"]),
    ("M3", "Judge agreement by the raw rate instead of the Wilson lower bound",
     [(L + "autonomy/Ladder.java", "double bound = figures.lowerBound() * 100;", "double bound = figures.rate() * 100;")], ["V3.3"]),
    ("M4", "Skip the epoch change when the agent's identity changes",
     [(L + "execution/Decider.java", "if (!identity.equals(state.identity())) state = newEpoch(def, engine, scope, state, identity);", "if (false) state = newEpoch(def, engine, scope, state, identity);")], ["V6.2"]),
    ("M5", "Let a replay run a tool that is not known to be a read",
     [(L + "execution/EvidenceTool.java", "        replay.simulated(name);\n        return SimulatingTool.SIMULATED;", "        return ReadClass.runLive(real, args);")], ["V7.5"]),
    ("M6", "Let a replay run on past the proposal instead of stopping there",
     [(L + "execution/Decider.java", '        run.stopPoint(step + "#decide-proposal");\n        // no stop point named', '        // no stop point named')], ["V7.4"]),
    ("M7", "Ignore the ceiling when judging a promotion",
     [(L + "autonomy/Ladder.java", "if (def.getCeiling().compareTo(next) < 0) {", "if (false) {")], ["V3.8"]),
    ("M8", "Drop the dangerous-mistake part of a rule",
     [(L + "autonomy/Ladder.java", "if (rule.noDangerous()) parts.add(", "if (false && rule.noDangerous()) parts.add(")], ["V3.4"]),
    ("M9", "Use the level now in force instead of the journaled one on a resumed case",
     [(L + "execution/Decider.java", "if (known.isPresent() && known.get().value() instanceof Map<?, ?> m) return castMap(m);", "if (false && known.isPresent() && known.get().value() instanceof Map<?, ?> m) return castMap(m);")], ["V3.10"]),
    ("M10", "Promote without waiting for the approver",
     [(L + "autonomy/Engine.java", "            if (def.isAutomatic()) {", "            if (true) {")], ["V3.9"]),
    ("M11", "Make audit sampling depend on something other than the case id",
     [(L + "execution/Decider.java", '(caseId + "|" + decision + "|audit")', '(caseId + "|" + decision + "|audit" + System.nanoTime())')], ["V3.6"]),
    ("M12", "Let a freeze apply to a case already in progress",
     [(L + "execution/Decider.java", "        if (ranAt == Level.ACT) {\n            verdict = text(proposal.get(\"choice\"));", "        if (ranAt == Level.ACT && !levels().frozen(def.getName())) {\n            verdict = text(proposal.get(\"choice\"));")], ["V5.4"]),
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
        if 'Tag("EA-' not in src:
            continue
        pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
        for m in re.finditer(r"((?:\s*@[\w.]+(?:\([^)]*\))?)+)\s*(?:public |private |protected )?(?:static )?void (\w+)\(", src):
            found = set(re.findall(r'@Tag\("EA-(V\d+\.\d+)"\)', m.group(1)))
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
                              cwd=MODULE, capture_output=True, text=True, timeout=2400)
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
    out = ["# G3: sabotage runs", "", "Each row breaks one rule in the real source, runs the autonomy tests, and restores the source.",
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
