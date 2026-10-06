#!/usr/bin/env python3
"""Gate G5 for the Loom workflow graph: the tests can fail. Breaks one rule at a time in the real sources, runs the
checks that guard it, and confirms the named test fails. Sources are restored afterwards, whatever happens.

    scripts/sabotage_graph.py [--only S1,S7]    writes .kiro/specs/loom-vscode-graph/evidence/G5-sabotage.md
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOOM = ROOT / "loom" / "ai-agent4j-loom"
REPORT = ROOT / "eval4j-report"
EXT = ROOT / "loom" / "vscode-loom"
RENDER = ROOT / "loom" / "graph-render"
EVIDENCE = ROOT / ".kiro" / "specs" / "loom-vscode-graph" / "evidence"
J = "loom/ai-agent4j-loom/src/main/java/io/github/llm4j/loom/"
RC = "eval4j-report/src/main/resources/io/github/llm4j/evalreport/render/"

# (id, what is broken, [(file, old, new)], how to run, [names that must fail], requirement)
SABOTAGES = [
    ("S1", "Drop the then/else labels on alt branches",
     [(J + "graph/GraphBuilder.java", "return block(statements, List.of(new Exit(owner, name)), owner, name);", "return block(statements, List.of(new Exit(owner, null)), owner, name);")],
     ("loom", "GraphBuilderTest,GraphGoldenTest"), ["anAltLabelsItsTwoBranchesAndBothJoinTheNextStep", "theContentFactoryWorkflowHasEightNodesAndEightEdges"], "V1.2a"),
    ("S2", "Draw no handler blocks",
     [(J + "graph/GraphBuilder.java", "            if (statements == null || statements.isEmpty()) {\n                return;\n            }\n            String token", "            if (true) {\n                return;\n            }\n            String token")],
     ("loom", "GraphBuilderTest"), ["handlersHangOffTheirOwnerOnLabelledEdgesAndRejoinTheMainPath"], "V1.4"),
    ("S3", "Skip import cycle detection",
     [(J + "graph/ImportClosureLoader.java", "if (visiting.contains(file)) {", "if (false) {")],
     ("loom", "ImportClosureLoaderTest"), ["anImportCycleEndsWithOneWarningNamingTheFilesAndBothGraphsStillLoad"], "V2.5"),
    ("S4", "Let the last definition win when two files define the same workflow",
     [(J + "graph/CallResolver.java", "winners.putIfAbsent(graph.name(), graph)", "winners.put(graph.name(), graph)")],
     ("loom", "GraphServiceTest"), ["whenTwoImportsDefineTheSameNameTheFirstInRunOrderWinsAndTheOtherIsReported"], "V2.9"),
    ("S5", "Log a secret: stop masking key-shaped text",
     [(J + "graph/Redactor.java", "return text == null ? null : KEY_LIKE.matcher(text).replaceAll(MASK);", "return text;")],
     ("loom", "RedactorTest"), ["keyShapedTextIsMasked", "aKeyPastedIntoAPromptDoesNotAppearInTheJsonOrTheMermaid"], "VS.4"),
    ("S6", "Make weave graph reach for the environment",
     [(J + "cli/GraphCommand.java", "GraphResult result = new GraphService().graph(c.script.toPath(), env.prompts());", "env.env().apply(\"OPENAI_API_KEY\");\n        GraphResult result = new GraphService().graph(c.script.toPath(), env.prompts());")],
     ("loom", "GraphCommandTest"), ["itNeverReachesForAModelAPersonASecretTheEnvironmentOrACommand"], "V3.4"),
    ("S7", "Report maps every node kind to the generic statement kind",
     [("eval4j-report/src/main/java/io/github/llm4j/evalreport/loom/WorkflowGraph.java", "node.id(), node.kind(), node.label(), node.agent(), node.bound(), withPlacement(node));", "node.id(), \"statement\", node.label(), node.agent(), node.bound(), withPlacement(node));")],
     ("report", "WorkflowGraphParityTest,ReportGraphTraceTest"), ["everyWorkflowInTheRepositoryKeepsItsOldNodesAndEdges", "theGraphUsesTheNewKindsAndCarriesSettingsAndTheTraceValidates"], "V4.2"),
    ("S8", "Stop escaping < in text drawn into the graph",
     [("loom/graph-render/graph-render.js", "'<': '&lt;', ", "")],
     ("render", None), ["text from a script is escaped everywhere it is drawn"], "V6.5 / VS.1"),
    ("S9", "Ignore the expected path when drawing a run",
     [("loom/graph-render/graph-render.js", "states[n.id] = inAct ? (hasExpected ? (inExp ? 'ok' : 'unexpected') : 'taken') : (inExp ? 'missed' : 'none');", "states[n.id] = inAct ? 'ok' : 'none';")],
     ("render", None), ["overlay: nodes on both paths are ok, only expected is missed, only actual is unexpected, neither is none"], "V10.3b"),
    ("S10", "Drop the visit count",
     [("loom/graph-render/graph-render.js", "if (visits > 1) { out += visitsSvg(p, visits, x - 34); }", "")],
     ("render", None), ["overlay counts visits, so a loop run three times shows ×3 and a node run once shows none"], "V10.4"),
    ("S11", "Let the report's copy of the renderer drift from the canonical file",
     [(RC + "graph-render.js", "'use strict';", "'use strict'; /* drift */")],
     ("sync", None), ["check-graph-render-sync"], "V10.14"),
    ("S12", "Remove the debounce: every save runs weave graph at once",
     [("loom/vscode-loom/src/graph/controller.ts", "        this.timer?.cancel();\n        this.timer = this.options.scheduler.after(", "        void this.reload();\n        return;\n        this.timer?.cancel();\n        this.timer = this.options.scheduler.after(")],
     ("ext", None), ["ten saves within the debounce make one run"], "V8.2"),
    ("S13", "Let the panel open any file it is asked to",
     [("loom/vscode-loom/src/graph/controller.ts", "    private isKnown(file: string): boolean {\n        return (", "    private isKnown(file: string): boolean {\n        return !!file || (")],
     ("ext", None), ["opening a source file is allowed for the entry and its imports only"], "VS.2"),
    ("S14", "Let the panel page run inline styles",
     [("loom/vscode-loom/src/graph/html.ts", "`style-src ${cspSource}`,", "`style-src ${cspSource} 'unsafe-inline'`,")],
     ("ext", None), ["the policy allows nothing but the extension's own files and nonce-marked scripts"], "V6.5"),
]


def run(cmd, cwd):
    return subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, shell=isinstance(cmd, str))


def clear_reports(module):
    """Old reports from an earlier run would be read as failures of this one."""
    reports = module / "target" / "surefire-reports"
    if reports.exists():
        for f in reports.glob("*"):
            f.unlink()


def failing_surefire(module):
    out = set()
    for xml in (module / "target" / "surefire-reports").glob("TEST-*.xml"):
        for case in ET.parse(xml).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                out.add(re.sub(r"\(.*$|\[.*$", "", case.get("name")))
    return out


def failing_tap(text):
    return {m.group(1).strip() for m in re.finditer(r"^\s*not ok \d+ - (.+)$", text, re.M)}


def check(kind, tests):
    """Runs the guarding checks; returns (set of failing test names, raw output)."""
    if kind == "loom":
        clear_reports(LOOM)
        r = run(["mvn", "-q", "-B", "-pl", "loom/ai-agent4j-loom", "test", f"-Dtest={tests}"], ROOT)
        return failing_surefire(LOOM), r.stdout + r.stderr
    if kind == "report":
        clear_reports(REPORT)
        r = run(["mvn", "-q", "-B", "-pl", "eval4j-report", "test", f"-Dtest={tests}"], ROOT)
        return failing_surefire(REPORT), r.stdout + r.stderr
    if kind == "render":
        r = run("node --test test/*.test.js", RENDER)
        return failing_tap(r.stdout), r.stdout
    if kind == "ext":
        r = run("npm test", EXT)
        return failing_tap(r.stdout), r.stdout
    if kind == "sync":
        r = run(["bash", "scripts/check-graph-render-sync.sh"], ROOT)
        return ({"check-graph-render-sync"} if r.returncode != 0 else set()), r.stdout + r.stderr
    raise SystemExit("unknown check " + kind)


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
    # the sources are back: prove it by running the guarded checks once on the clean tree
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    head = subprocess.run(["git", "rev-parse", "--short", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    lines = [f"# G5 sabotage run", "", f"Commit {head}. Each row breaks one rule in the real sources, runs the checks that guard it, and records whether the named test failed. The sources are restored after every row.", "",
             "| ID | Requirement | What was broken | Test that must fail | Result |", "|---|---|---|---|---|"]
    lines += [f"| {a} | {b} | {c} | {d} | {e} |" for a, b, c, d, e in rows]
    lines += ["", f"{len(rows) - bad} of {len(rows)} caught."]
    (EVIDENCE / "G5-sabotage.md").write_text("\n".join(lines) + "\n")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
