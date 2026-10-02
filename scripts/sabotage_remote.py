#!/usr/bin/env python3
"""Gate G3 for remote answers: the tests can fail. Breaks one rule at a time in the real sources, runs the channel tests, and checks that tests
tagged with the expected checks fail. Sources are restored afterwards, whatever happens.

    scripts/sabotage_remote.py [--only M1,M7]      writes .kiro/specs/loom-remote-answers/evidence/G3-sabotage.md
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
SPEC = ROOT / ".kiro" / "specs" / "loom-remote-answers"
L = "loom/ai-agent4j-loom/src/main/java/io/github/llm4j/loom/"
TESTS = "io.github.llm4j.loom.channel.*Test,ChannelRunTest,ChannelGuideTest"

SABOTAGES = [
    ("M1", "Ignore the allowlist (anyone may answer)",
     [(L + "channel/Listener.java", "return ok.contains(Long.parseLong(reply.chat())) && ok.contains(Long.parseLong(reply.sender()));", "return true;")], ["V3.3", "V5.1"]),
    ("M2", "Accept an answer twice",
     [(L + "channel/Answers.java", "switch (p.state()) {", "switch (Pending.State.OPEN) {")], ["V2.3"]),
    ("M3", "Match a coded reply to the wrong question",
     [(L + "channel/Listener.java", "if (target == null && codeToken != null) target = store.get(codeToken).orElse(null);", "if (target == null && codeToken != null) target = open.isEmpty() ? null : open.get(open.size() - 1);")], ["V3.4"]),
    ("M4", "Drop the code requirement for approvals",
     [(L + "channel/Listener.java", "if (target.approval() && !codeGiven) {", "if (false) {")], ["V5.3"]),
    ("M5", "Show the proposal in a watch question",
     [(L + "execution/Decider.java", "verdict = ask(def, step, fields, shown ? proposal : null);", "verdict = ask(def, step, fields, proposal);")], ["V5.4"]),
    ("M6", "Send the question again every time the run is resumed",
     [(L + "channel/ChannelHumanInterface.java", "if (p.sent().isEmpty()) {", "if (true) {"),
      (L + "channel/ChannelHumanInterface.java", "if (fresh.open() && fresh.sent().isEmpty() && dispatch.send(fresh)) store.put(fresh);", "if (fresh.open() && dispatch.send(fresh)) store.put(fresh);")], ["V1.4"]),
    ("M7", "Advance the offset before the replies are recorded",
     [(L + "channel/Listener.java", "Channel.Batch batch = channel.poll(wait);\n        int recorded = 0;", "Channel.Batch batch = channel.poll(wait);\n        channel.acknowledge(batch.cursor());\n        int recorded = 0;")], ["V3.6"]),
    ("M8", "Let the token through in an error",
     [(L + "channel/TelegramChannel.java", 'if (!token.isEmpty()) t = t.replace(token, "<token>");', "")], ["V3.1"]),
    ("M9", "Treat an empty allowlist as everyone",
     [(L + "channel/ChannelConfig.java", 'if (allowedIds().isEmpty()) return "nobody is allowed', 'if (false) return "nobody is allowed')], ["V5.7"]),
    ("M10", "Let a reply written before the question answer it",
     [(L + "channel/Listener.java", "if (olderThanQuestion(reply, target)) {", "if (false) {")], ["V3.8"]),
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
        if 'Tag("RA-' not in src:
            continue
        pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
        for m in re.finditer(r"((?:\s*@[\w.]+(?:\([^)]*\))?)+)\s*(?:public |private |protected )?(?:static )?void (\w+)\(", src):
            found = set(re.findall(r'@Tag\("RA-(V\d+\.\d+)"\)', m.group(1)))
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
    out = ["# G3: sabotage runs", "", "Each row breaks one rule in the real source, runs the channel tests, and restores the source.",
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
