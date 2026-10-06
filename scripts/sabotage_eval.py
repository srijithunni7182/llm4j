#!/usr/bin/env python3
"""Verification of loom-weave-eval: the tests can fail. Breaks one rule at a time in the real sources, runs the checks that guard it,
and confirms the named test fails. Sources are restored afterwards, whatever happens. Reuses the engine of sabotage_graph.py.

    scripts/sabotage_eval.py [--only E1,E4]    writes .kiro/specs/loom-weave-eval/evidence/sabotage.md
"""
import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sabotage_graph as engine  # noqa: E402

ROOT = engine.ROOT
EVIDENCE = ROOT / ".kiro" / "specs" / "loom-weave-eval" / "evidence"
J = engine.J
E = "eval4j/src/main/java/io/github/llm4j/eval/"

SABOTAGES = [
    ("E1", "Count a line nothing judged as met",
     [(J + "eval/EvalRunner.java", 'checks.add(new ScenarioResult.Check(kind, line, Status.UNJUDGED, "no judge ran"));', 'checks.add(new ScenarioResult.Check(kind, line, Status.PASS, "no judge ran"));')],
     ("loom", "EvalRunnerTest,EvalCommandTest"), ["withNoJudgeARubricLineIsUnjudgedNeverAPass"], "R3.4 / R6.3"),
    ("E2", "Let unjudged outrank a failure when a scenario's checks are combined",
     [(J + "eval/Status.java", "return rank() >= other.rank() ? this : other;", "return ordinal() >= other.ordinal() ? this : other;")],
     ("loom", "EvalRunnerTest"), ["aFailureBeatsUnjudgedWhichBeatsAPass"], "R6.3"),
    ("E3", "Ignore the limit: neither give each scenario what is left nor stop when it is reached",
     [(J + "cli/EvalCommand.java", "return (tokens != null && usedTokens() >= tokens) || (calls != null && usedCalls() >= calls) || (cost != null && usedCost().compareTo(cost) >= 0);", "return false;"),
      (J + "cli/EvalCommand.java", "if (!c.mock) e.setBudgetOverrides(spend.remainingTokens(), spend.remainingCalls(), spend.remainingCost());", "")],
     ("loom", "EvalCommandTest"), ["r3_5_aLimitThatIsReachedStopsTheRunAndCountsWhatWasNotRun"], "R3.5"),
    ("E4", "Make weave check need a dataset",
     [(J + "cli/WeaveCLI.java", "        io.github.llm4j.loom.ast.LoomScript script;\n        try {\n            script = new LoomLoader().load(scriptFile.getAbsolutePath());",
       "        if (!java.nio.file.Files.isDirectory(scriptFile.getAbsoluteFile().getParentFile().toPath().resolve(\"eval\"))) { env.out().println(\"no dataset\"); return 2; }\n        io.github.llm4j.loom.ast.LoomScript script;\n        try {\n            script = new LoomLoader().load(scriptFile.getAbsolutePath());")],
     ("loom", "EvalCommandTest"), ["r4_1_checkAndRunNeverNeedADatasetOrAnEvalFolder"], "R4.1"),
    ("E5", "Start a real run without asking",
     [(J + "cli/EvalCommand.java", "if (!c.yes && !env.human().promptHuman", "if (false && !env.human().promptHuman")],
     ("loom", "EvalCommandTest"), ["r6_1_aRealRunSaysWhatItWillDoAndAsksFirst_andNoMeansNothingRuns"], "R6.1"),
    ("E6", "Let a mock run use the real models",
     [(J + "cli/EvalCommand.java", "LLMClientFactory models = c.mock ? new MockModels() : env.models();", "LLMClientFactory models = env.models();")],
     ("loom", "EvalCommandTest"), ["r3_5_aMockRunNeverTouchesTheRealModelsAndJudgesNothing", "theShippedNewsletterExampleRunsInAMockRun"], "R3.5"),
    ("E7", "Overwrite a dataset file that is already there",
     [(J + "cli/EvalCommand.java", "        if (Files.exists(file)) {\n            kept.add(file.getFileName().toString());", "        if (false) {\n            kept.add(file.getFileName().toString());")],
     ("loom", "EvalCommandTest"), ["r4_5_initCreatesAStarterDatasetAndNeverOverwritesAFile"], "R4.5"),
    ("E8", "Put dataset text into the report page unescaped",
     [(J + "eval/EvalReport.java", 'return s.replace("&", "&amp;").replace("<", "&lt;")', 'return s.replace("&", "&amp;")')],
     ("loom", "EvalReportTest"), ["theHtmlEscapesEverythingFromTheDatasetAndRunsNothing"], "R6.2"),
    ("E9", "Stop reading the older RUBRIC:/EXPECT: lines in context",
     [(E + "dataset/EvalScenarios.java", 'if (s.context() == null || s.context().stream().noneMatch(c -> c.startsWith("RUBRIC:") || c.startsWith("EXPECT:"))) {', "if (true) {")],
     ("eval4j", "EvalScenarioFieldsTest"), ["r2_2_theOlderConventionInContextIsReadAsTheFields"], "R2.2"),
    ("E10", "Keep the script's own declaration of a tool that has fixtures",
     [(J + "eval/Fixtures.java", "script.getTools().removeIf(def -> replacements.containsKey(def.getName()));", "")],
     ("loom", "FixturesTest"), ["aFixtureToolReplacesTheScriptsDeclarationOfThatTool", "inAMockRunEveryToolTheAgentsUseIsAnsweredWithoutTheOutsideWorld"], "R3.9"),
    ("E11", "Check the content of a mock answer",
     [(J + "eval/EvalRunner.java", "        if (mock) {\n            if (s.expectedOutputContains() != null)", "        if (false) {\n            if (s.expectedOutputContains() != null)")],
     ("loom", "EvalRunnerTest,EvalCommandTest"), ["inAMockRunContentChecksAreUnjudgedBecauseAMockSaysNothingAboutContent"], "R3.2"),
    ("E12", "Match dataset files to agents by exact case only",
     [(J + "eval/DatasetFolder.java", "for (String n : names) if (n.equalsIgnoreCase(stem)) return n;", "for (String n : names) if (n.equals(stem)) return n;")],
     ("loom", "DatasetFolderTest"), ["anAgentFileMatchesWhateverTheCaseAndAWorkflowFileMatchesItsName"], "R1.1"),
]

_check = engine.check


def check(kind, tests):
    if kind == "eval4j":
        engine.clear_reports(ROOT / "eval4j")
        r = engine.run(["mvn", "-q", "-B", "-pl", "eval4j", "test", f"-Dtest={tests}"], ROOT)
        return engine.failing_surefire(ROOT / "eval4j"), r.stdout + r.stderr
    return _check(kind, tests)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    only = {s for s in ap.parse_args().only.split(",") if s}
    rows, bad = [], 0
    for sid, what, edits, (kind, tests), expected, req in SABOTAGES:
        if only and sid not in only:
            continue
        originals = {}
        try:
            for rel, old, new in edits:
                path = ROOT / rel
                text = path.read_text()
                if old not in text:
                    raise SystemExit(f"{sid}: the code to break is not in {rel} any more: {old[:60]!r}")
                originals.setdefault(path, text)
                path.write_text(text.replace(old, new, 1))
            failed, output = check(kind, tests)
            caught = [e for e in expected if any(e in f for f in failed)]
            ok = len(caught) == len(expected)
            print(f"{sid} {'CAUGHT' if ok else 'MISSED'}  {what}  ({len(failed)} failing: {', '.join(sorted(failed))[:160]})")
            if not ok:
                bad += 1
                print(output[-1500:])
            rows.append((sid, req, what, ", ".join(expected), "caught" if ok else "NOT CAUGHT"))
        finally:
            for path, text in originals.items():
                path.write_text(text)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    lines = ["# weave eval: sabotage run", "", f"Commit {head}. Each row breaks one rule in the real sources, runs the checks that guard it, and records whether the named test failed. The sources are restored after every row.", "",
             "| ID | Requirement | What was broken | Test that must fail | Result |", "|---|---|---|---|---|"]
    lines += [f"| {a} | {b} | {c} | {d} | {e} |" for a, b, c, d, e in rows]
    lines += ["", f"{len(rows) - bad} of {len(rows)} caught."]
    (EVIDENCE / "sabotage.md").write_text("\n".join(lines) + "\n")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
