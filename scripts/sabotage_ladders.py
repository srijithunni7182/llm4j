#!/usr/bin/env python3
"""Verification H4 for ladders: every protection can be removed and a test notices. Breaks one safeguard at a time in the real sources, runs the
ladder tests, and requires at least one failure. Sources are restored afterwards, whatever happens.

    scripts/sabotage_ladders.py [--only S1,S7]      writes .kiro/specs/loom-ladders/evidence/H4-sabotage.md
"""
import argparse
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULE = ROOT / "src" / "loom" / "ai-agent4j-loom"
REPORTS = MODULE / "target" / "surefire-reports"
SPEC = ROOT / ".kiro" / "specs" / "loom-ladders"
L = "src/loom/ai-agent4j-loom/src/main/java/io/github/llm4j/loom/"
TESTS = ("Ladder*Test,Reference*Test,Interpreter*Test,Expr*Test,LedgerFoldTest,VTest,LexerTest,CheckerTest,WilsonTest,DistanceTest,StandardLibraryTest,"
         "TypicalMistakesTest,NovelAgentsTest,LadderVerificationTest,LadderStoresTest,LadderScriptTest,!LadderScaleTest,"
         "Trust*Test,AgentLadderDefaultTest,DecideSafetyTest,DecideResilienceTest,ReplayTest,ArtifactReferenceTest,LadderEpochReplayTest")

SABOTAGES = [
    ("S1", "Missing data makes a condition true (fail open) instead of false",
     [(L + "autonomy/expr/Interpreter.java", "return V.isTrue(eval(e, ctx));", "V v = eval(e, ctx); return !(v instanceof V.Bool) || ((V.Bool) v).value();")]),
    ("S2", "Show the proposal to the person at a blind rung",
     [(L + "execution/LadderDecider.java", "else answerChoice(def, step, fields, shown ? proposal : null, proposedText, result);", "else answerChoice(def, step, fields, proposal, proposedText, result);")]),
    ("S3", "Let anyone approve a promotion",
     [(L + "ladder/LadderEngine.java", 'if (policy.approver() != null && !policy.approver().equals(by)) {\n            throw new IllegalStateException("only " + policy.approver() + " may approve', 'if (false) {\n            throw new IllegalStateException("only " + policy.approver() + " may approve')]),
    ("S4", "Ignore the ceiling when climbing",
     [(L + "ladder/LadderEngine.java", "if (to > policy.ceiling() || to >= policy.rungs().size()", "if (to >= policy.rungs().size()")]),
    ("S5", "Keep the old agent's standing when the agent changes",
     [(L + "ladder/LadderEngine.java", "if (!cur.identity().equals(identity)) {", "if (false) {")]),
    ("S6", "Ignore a change of the model the provider says it served",
     [(L + "ladder/LadderEngine.java", "} else if (known(cur.servedModel()) && known(servedModel) && !cur.servedModel().equals(servedModel)) {", "} else if (false) {")]),
    ("S7", "Do not apply a slip",
     [(L + "ladder/LadderEngine.java", "            if (slip.isPresent()) {\n                String why = because(policy.explainSlip", "            if (false && slip.isPresent()) {\n                String why = because(policy.explainSlip")]),
    ("S8", "Let two writers both believe they made the same change",
     [(L + "ladder/LevelLog.java", "if (r.id().equals(rec.id())) return writer.equals(r.str(\"writer\"));", "if (r.id().equals(rec.id())) return true;")]),
    ("S9", "Make sampling depend on something other than the case",
     [(L + "ladder/LadderPolicy.java", '+ "|sample").getBytes', '+ "|sample" + System.nanoTime()).getBytes')]),
    ("S10", "Let a freeze have no effect on the rung a case runs at",
     [(L + "ladder/LadderEngine.java", "        if (log.frozen()) {\n            int fa = policy.firstAutonomous();", "        if (false) {\n            int fa = policy.firstAutonomous();")]),
    ("S11", "Judge agreement by the raw share instead of the cautious estimate",
     [(L + "autonomy/expr/V.java", "return new Rat((double) k / n, n, Wilson.lower(k, n), Wilson.upper(k, n));", "return new Rat((double) k / n, n, (double) k / n, (double) k / n);")]),
    ("S12", "Do not demote when the rules are tightened",
     [(L + "ladder/LadderEngine.java", "                    if (!policy.climbs(r, history, t)) {\n                        why =", "                    if (false) {\n                        why =")]),
    ("S13", "Never send an escalated case to a person",
     [(L + "ladder/LadderEngine.java", "if (policy.escalates(rung, current, history, nowMillis)) return", "if (false) return")]),
    ("S14", "Count the old epoch's cases as evidence",
     [(L + "ladder/HistoryBuilder.java", "if (epoch >= 0 && c.epoch() != epoch) continue;", "")]),
    ("S15", "Count a superseded generation of a case",
     [(L + "ladder/HistoryBuilder.java", "if (c.superseded() || !c.responded() || !c.scope().equals(scope)) continue;", "if (!c.responded() || !c.scope().equals(scope)) continue;")]),
    ("S16", "Let the agent act alone when the ledger cannot be read",
     [(L + "execution/LadderDecider.java", 'pick.put("mode", RungDef.Mode.BLIND.word());\n            pick.put("how", "degraded");', 'pick.put("mode", RungDef.Mode.AUTONOMOUS.word());\n            pick.put("how", "degraded");')]),
    ("S17", "Let an unusable proposal take effect at the autonomous rung",
     [(L + "execution/LadderDecider.java", "if (mode == RungDef.Mode.AUTONOMOUS && malformed) {", "if (false) {")]),
    ("S18", "Promote on a climb that the next rung would undo at once",
     [(L + "ladder/LadderEngine.java", "if (policy.slip(to, history, t).isPresent()) return out;", "")]),
    ("S19", "Allow a measure to call itself",
     [(L + "autonomy/expr/Checker.java", "if (inProgress.contains(name)) {", "if (false) {")]),
    ("S20", "Let a measure read the history",
     [(L + "autonomy/expr/Checker.java", "if ((name.equals(\"history\") || name.equals(\"case\")) && s.inMeasure()) {", "if (false) {")]),
    ("S21", "Re-judge (and demote) a standing the original engine earned, when a decision moves to the ladder engine",
     [(L + "ladder/LadderEngine.java", "if (cur.rules().equals(LevelLog.LEGACY)) {", "if (false) {")]),
    ("S22", "Hide the cases a ladder wrote from the replay feature",
     [(L + "autonomy/Cases.java", "case Rec.RESPONDED -> responded = r;", "case Rec.RESPONDED -> { }")]),
    ("S23", "Let a case change rung after the agent has replied (a freeze arriving mid-case)",
     [(L + "execution/LadderDecider.java", "&& Decider.text(Decider.castMap(began).get(\"epoch\")).equals(String.valueOf(st.epoch()))) {", "&& false) {")]),
    ("S24", "When two ways down hold, take the first instead of the lowest",
     [(L + "ladder/LadderPolicy.java", "if (best == null || to < best.to()) best", "if (best == null) best")]),
    ("S25", "Write the trust block without its days requirement",
     [(L + "ladder/TrustDesugar.java", "if (rule.days() > 0) parts.add(said(", "if (false) parts.add(said(")]),
    ("S27", "Keep a large artifact inside the record of the case instead of apart",
     [(L + "ladder/Blobs.java", "public static final int LIMIT = 16 * 1024;", "public static final int LIMIT = Integer.MAX_VALUE;")]),
    ("S28", "Retention leaves the text of a large artifact in place",
     [(L + "autonomy/Purge.java", 'if (body.remove("text") != null) body.put("purged", true);', 'body.remove("size");')]),
    ("S26", "Count every case, not only blind ones, as evidence for a trust block",
     [(L + "ladder/TrustDesugar.java", 'measure(BLIND, "c.blind", line)', 'measure(BLIND, "true", line)')]),
]


def failing():
    out = set()
    for xml in REPORTS.glob("TEST-*.xml"):
        for case in ET.parse(xml).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                out.add((case.get("classname").split(".")[-1], re.sub(r"\(.*$|\[.*$", "", case.get("name"))))
    return out


def run_one(edits):
    originals = {}
    try:
        for path, old, new in edits:
            f = ROOT / path
            originals.setdefault(f, f.read_text())
            text = f.read_text()
            if text.count(old) != 1:
                return "NOT APPLIED", f"expected exactly one match for {old[:70]!r} in {path}, found {text.count(old)}", []
            f.write_text(text.replace(old, new))
        shutil.rmtree(REPORTS, ignore_errors=True)
        proc = subprocess.run(["mvn", "-B", "-q", "test", f"-Dtest={TESTS}", "-DfailIfNoTests=false", "-Dsurefire.failIfNoSpecifiedTests=false", "-Djacoco.skip=true"],
                              cwd=MODULE, capture_output=True, text=True, timeout=2400)
        out = proc.stdout + proc.stderr
        if "COMPILATION ERROR" in out:
            return "COMPILE ERROR", " ".join(l for l in out.splitlines() if "ERROR" in l and ".java" in l)[:300], []
        if not list(REPORTS.glob("TEST-*.xml")):
            return "NO REPORT", out[-400:], []
        bad = failing()
        names = sorted(f"{c}.{m}" for c, m in bad)
        if not bad:
            return "SURVIVED", "no test failed", []
        return "DETECTED", f"{len(bad)} test(s) failed", names
    finally:
        for f, text in originals.items():
            f.write_text(text)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    only = {s for s in ap.parse_args().only.split(",") if s}
    rows = []
    for sid, description, edits in SABOTAGES:
        if only and sid not in only:
            continue
        print(f"{sid}: {description} ...", flush=True)
        status, detail, names = run_one(edits)
        print(f"   -> {status}: {detail}", flush=True)
        rows.append((sid, description, status, detail, names))
    out = ["# H4: sabotage runs", "", "Each row breaks one safeguard in the real source, runs the ladder tests, and restores the source.",
           "**DETECTED** means at least one test failed; a safeguard whose removal fails nothing is untested.", "",
           "| # | Sabotage | Result | Detail | Failing tests |", "|---|---|---|---|---|"]
    for sid, desc, status, detail, names in rows:
        shown = ", ".join(names[:5]) + (f" (+{len(names) - 5} more)" if len(names) > 5 else "")
        out.append(f"| {sid} | {desc} | **{status}** | {detail} | {shown} |")
    evidence = SPEC / "evidence"
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / ("H4-sabotage.md" if not only else "H4-sabotage-partial.md")).write_text("\n".join(out) + "\n")
    bad = [r for r in rows if r[2] != "DETECTED"]
    print(f"\n{len(rows) - len(bad)}/{len(rows)} detected" + ("" if not bad else "; NOT detected: " + ", ".join(f"{r[0]} ({r[2]})" for r in bad)))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
