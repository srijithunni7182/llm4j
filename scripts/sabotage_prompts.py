#!/usr/bin/env python3
"""Verification of loom-prompt-files: the tests can fail. Breaks one rule at a time in the real sources, runs the checks that guard
it, and confirms the named test fails. Sources are restored afterwards, whatever happens. Reuses the engine of sabotage_graph.py.

    scripts/sabotage_prompts.py [--only P1,P4]    writes .kiro/specs/loom-prompt-files/evidence/sabotage.md
"""
import argparse
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import sabotage_graph as engine  # noqa: E402

ROOT = engine.ROOT
EVIDENCE = ROOT / ".kiro" / "specs" / "loom-prompt-files" / "evidence"
J = engine.J
REG = "ai-agent4j/src/main/java/io/github/llm4j/agent/prompt/MarkdownFolderPromptRegistry.java"

SABOTAGES = [
    ("P1", "Follow a link that leaves the prompt folder",
     [(REG, "if (!real.startsWith(root.toRealPath())) {", "if (false) {")],
     ("agent", "MarkdownFolderPromptRegistryTest"), ["aLinkThatLeavesTheFolderIsRefused"], "R6.1"),
    ("P2", "Read a prompt file of any size",
     [(REG, "if (size > MAX_BYTES) {", "if (false) {")],
     ("agent", "MarkdownFolderPromptRegistryTest"), ["aFileOverTheLimitIsRefusedByName"], "R1.5"),
    ("P3", "Take 'latest' as the last version in sort order, not the highest number",
     [(REG, "TreeMap<Integer, Entry> versions = id == null ? null : prompts.get(id);\n        return versions == null || versions.isEmpty() ? Optional.empty() : Optional.of(versions.lastEntry().getValue());",
       "TreeMap<Integer, Entry> versions = id == null ? null : prompts.get(id);\n        return versions == null || versions.isEmpty() ? Optional.empty() : Optional.of(versions.firstEntry().getValue());")],
     ("agent", "MarkdownFolderPromptRegistryTest"), ["theLatestVersionIsTheHighestNumberNotTheLastAlphabetically"], "R1.4"),
    ("P4", "Leave the prompt text out of an agent's identity",
     [(J + "autonomy/AgentIdentity.java", 'return prompts.resolve(reference).map(r -> "@" + r.version() + "#" + sha(r.text())).orElse("@unresolved");', 'return "";')],
     ("loom", "PromptIdentityTest"), ["editingThePromptFileMakesADifferentAgent", "pinningAnotherVersionIsADifferentAgentAndPinningTheLatestIsNot"], "R4.1"),
    ("P5", "Ignore the command-line pin",
     [(J + "prompt/PromptCatalog.java", "return pinned != null ? pinned : ref.version();", "return ref.version();")],
     ("loom", "PromptFilesTest,PromptAbTest"), ["r2_5_aCommandLinePinBeatsTheScriptsVersion", "pinningAnotherVersionChangesTheResearchersPromptAndNothingElse"], "R2.5"),
    ("P6", "Accept any text as a prompt reference",
     [(J + "prompt/PromptRef.java", 'Pattern.compile("([a-z0-9][a-z0-9_-]*)(?:@(v[0-9]+))?")', 'Pattern.compile("(.*?)(?:@(v[0-9]+))?")')],
     ("loom", "PromptFilesTest"), ["r2_1_aMalformedReferenceIsAParseErrorThatShowsTheForm"], "R2.1 / R6.1"),
    ("P7", "Run a script whose prompt file is missing instead of failing the load",
     [(J + "execution/ScriptValidator.java", "if (problem != null) c.error(a.getLine(), who, problem);", "if (false) c.error(a.getLine(), who, problem);")],
     ("loom", "PromptFilesTest,PromptCliTest"), ["r3_1_aMissingPromptNamesTheNearestIdsAndWhereToPutTheFile", "checkSaysWhatIsMissingAndWhereToPutIt"], "R3.1 / R3.3"),
    ("P8", "Forget the folder a script names with prompts:",
     [(J + "execution/LoomLoader.java", "effectiveScript.setPromptsDir(script.getPromptsDir(), script.getPromptsDirLine());", "")],
     ("loom", "PromptFilesTest"), ["anImportedFileCannotChooseTheFolder"], "R2.4"),
    ("P9", "Put the prompt text in the audit's hash column instead of a hash of it",
     [(J + "prompt/PromptCatalog.java", "x.id(), x.version(), hash(x.text()), x.file())", "x.id(), x.version(), x.text(), x.file())")],
     ("loom", "PromptCliTest"), ["auditListsWhichPromptEachAgentRunsWithAHashAndNeverTheText"], "R6.3"),
    ("P10", "Let the panel open any file once an agent has a prompt file",
     [("loom/vscode-loom/src/graph/controller.ts", "!!this.current?.agents.some((a) => a.prompt?.file !== undefined && samePath(a.prompt.file, file))", "!!this.current?.agents.some((a) => a.prompt?.file !== undefined)")],
     ("ext", None), ["the panel may open the prompt file of an agent, and no other file in the same folder"], "R7.1"),
    ("P11", "Create a prompt file over one that is already there",
     [("loom/vscode-loom/src/prompts/createPromptFile.ts", "const missing = all.filter((ref) => !host.exists(promptFilePath(folder, ref)) && !host.exists(promptFilePath(folder, { ...ref, version: undefined })));", "const missing = all;")],
     ("ext", None), ["a prompt that already has a file is not offered, and a lone missing one is created without asking"], "R7.2"),
]

_check = engine.check


def check(kind, tests):
    if kind == "agent":
        engine.clear_reports(ROOT / "ai-agent4j")
        r = engine.run(["mvn", "-q", "-B", "-pl", "ai-agent4j", "test", f"-Dtest={tests}"], ROOT)
        return engine.failing_surefire(ROOT / "ai-agent4j"), r.stdout + r.stderr
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
                originals[path] = text
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
    lines = ["# Prompt files: sabotage run", "", f"Commit {head}. Each row breaks one rule in the real sources, runs the checks that guard it, and records whether the named test failed. The sources are restored after every row.", "",
             "| ID | Requirement | What was broken | Test that must fail | Result |", "|---|---|---|---|---|"]
    lines += [f"| {a} | {b} | {c} | {d} | {e} |" for a, b, c, d, e in rows]
    lines += ["", f"{len(rows) - bad} of {len(rows)} caught."]
    (EVIDENCE / "sabotage.md").write_text("\n".join(lines) + "\n")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
