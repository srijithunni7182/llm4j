#!/usr/bin/env python3
"""Gate G3: the tests can fail. Breaks one rule at a time in the real sources, runs the tests that should notice,
and checks that the expected checks fail. The sources are restored afterwards, whatever happens.

    scripts/sabotage_generic_tools.py                 # all
    scripts/sabotage_generic_tools.py --only S1,S14   # some
Writes .kiro/specs/loom-generic-tools/evidence/G3-sabotage.md.
"""
import argparse
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

from loomgen import MODULE, REPORT_DIRS, ROOT, SPEC, TOOLS, tagged_methods

MAIN = "ai-agent4j-tools/src/main/java/io/github/llm4j/tools/"   # paths are relative to the repository root
G = MAIN

SABOTAGES = [
    ("S1", "NetPolicy: stop unwrapping IPv4-mapped IPv6 addresses",
     [(G + "NetPolicy.java", "forbidden(unmap(a), loopbackOk)", "forbidden(a, loopbackOk)")], "NetPolicyTest", ["V3.2"]),
    ("S2", "HttpSupport: connect through a second, unchecked DNS lookup",
     [(G + "HttpSupport.java", "name -> name.equalsIgnoreCase(host) ? checked : Dns.SYSTEM.lookup(name)", "name -> Dns.SYSTEM.lookup(name)")], "HttpSupportTest", ["V3.3"]),
    ("S3", "RequestPath: accept // in a path",
     [(G + "RequestPath.java", 'if (path.contains("//")) throw', 'if (false) throw')], "HttpToolTest,HostileModelSuiteTest", ["V6.2", "H2"]),
    ("S4", "Redactor: skip the Base64 forms",
     [(G + "Redactor.java", "all.addAll(base64Fragments(secret.getBytes(StandardCharsets.UTF_8)));", "")], "RedactorTest", ["V1.5", "F4"]),
    ("S5", "A tool puts its URL in an error and its output isn't scrubbed",
     [(G + "GenericTool.java", "return new Outcome(redactor.scrub(outcome.text()), outcome.status());", "return outcome;"),
      (G + "WebhookTool.java", 'throw new ToolRefusal("the webhook answered HTTP " + reply.status()', 'throw new ToolRefusal("the webhook answered HTTP " + reply.status() + " from " + config.url()')],
     "WebhookToolTest,HostileModelSuiteTest", ["V1.5"]),
    ("S6", "EffectTool: don't write 'pending' before acting",
     [(G + "EffectTool.java", "            journal.put(key, new EffectJournal.Entry(PENDING, \"\"));\n", "")], "EffectToolTest,EffectsEndToEndTest", ["V2.3", "V2.4"]),
    ("S7", "EffectTool: treat a failed record as done",
     [(G + "EffectTool.java", "if (earlier != null && DONE.equals(earlier.kind())) {", "if (earlier != null && (DONE.equals(earlier.kind()) || FAILED.equals(earlier.kind()))) {")], "EffectToolTest", ["V2.7"]),
    ("S8", "PathGuard: allow hidden files",
     [(G + "PathGuard.java", 'if (!name.isEmpty() && name.startsWith("."))', "if (false)")], "FileToolTest,HostileModelSuiteTest", ["V7.3", "H4"]),
    ("S9", "SafePaths: skip the symlink check",
     [(MAIN + "SafePaths.java", "if (!realLocation(p).startsWith(realLocation(root)))", "if (realLocation(p) == null)")], "FileToolTest,SafePathsTest", ["V7.3"]),
    ("S10", "ShellTool: run through sh -c",
     [(G + "ShellTool.java", "ProcessBuilder builder = new ProcessBuilder(command)", 'ProcessBuilder builder = new ProcessBuilder(List.of("/bin/sh", "-c", String.join(" ", command)))')], "ShellToolTest", ["V8.1", "H5"]),
    ("S11", "ShellTool: don't clear the environment",
     [(G + "ShellTool.java", "        env.clear();\n", "")], "ShellToolTest", ["V8.5"]),
    ("S12", "ShellKind: skip the interpreter deny-list",
     [(G + "ShellKind.java", "if (!allowInterpreters && (WRAPPERS", "if (false && (WRAPPERS")], "ShellToolTest", ["V8.3"]),
    ("S13", "ShellKind: drop the approve-or-unattended rule",
     [(G + "ShellKind.java", 'if (approved || "true".equals(options.get("unattended"))) return null;', "if (true) return null;")], "ShellToolTest,ShellApprovalTest", ["V8.10"]),
    ("S14", "SqlGuard: stop forbidding INTO",
     [(G + "SqlGuard.java", '"EXECUTE", "INTO", "COPY"', '"EXECUTE", "COPY"')], "SqlGuardTest,SqlToolTest", ["V9.3", "F1", "H6"]),
    ("S15", "SqlTool: don't open the connection read-only",
     [(G + "SqlTool.java", "        connection.setReadOnly(true);\n", "")], "SqlToolTest", ["V9.4"]),
    ("S16", "SqlTool: put parameter values into the statement text",
     [(G + "SqlTool.java", "PreparedStatement statement = connection.prepareStatement(sql))", 'PreparedStatement statement = connection.prepareStatement(values.isEmpty() ? sql : sql.replace("?", "\'" + values.get(0) + "\'")))')], "SqlToolTest", ["V9.1"]),
    ("S17", "EmailTool: allow line breaks in the subject",
     [(G + "EmailTool.java", 'throw new ToolRefusal("subject must be a single line");', "{ }")], "EmailToolTest,EmailFuzzTest", ["V5.3", "F3"]),
    ("S18", "EmailTool: skip the allow_to check",
     [(G + "EmailTool.java", 'if (config.allowTo().stream().noneMatch(a::matches)) throw new ToolRefusal("that recipient is not allowed");', "")], "EmailToolTest", ["V5.2"]),
    ("S19", "SmtpSender: deliver to the valid recipients when one is refused",
     [(G + "SmtpSender.java", 'p.put("mail.smtp.sendpartial", "false");', 'p.put("mail.smtp.sendpartial", "true");')], "EmailToolTest", ["V5.11"]),
    ("S20", "EffectTool: count only finished calls against max_per_run",
     [(G + "EffectTool.java", ".filter(e -> PENDING.equals(e.getValue().kind()) || DONE.equals(e.getValue().kind()))", ".filter(e -> DONE.equals(e.getValue().kind()))")], "EffectToolTest,EmailToolTest", ["V5.12"]),
    ("S22", "HttpSupport: send credential headers on a redirect to another origin",
     [(G + "HttpSupport.java", "if (!sameOrigin(request.url(), target)) {", "if (false) {")], "HttpSupportTest", ["V3.5", "H2"]),
    ("S23", "PathGuard: allow control and line-break characters in a path",
     [(G + "PathGuard.java", "if (c < 0x20 || c == 0x7f || c == 0x85 || c == 0x2028 || c == 0x2029) {", "if (false) {")], "FileToolTest,EmailToolTest", ["V7.3", "H4"]),
    ("S21", "Limits: read the whole body",
     [(G + "Limits.java", "long room = maxBytes - total;", "long room = Long.MAX_VALUE;")], "HttpSupportTest,LimitsTest", ["V3.7"]),
]


def failing_methods():
    out = set()
    for xml in [x for d in REPORT_DIRS for x in d.glob("TEST-*.xml")]:
        for case in ET.parse(xml).getroot().iter("testcase"):
            if case.find("failure") is not None or case.find("error") is not None:
                out.add((case.get("classname"), re.sub(r"\(.*$|\[.*$", "", case.get("name"))))
    return out


def run_one(sid, description, edits, classes, expected):
    originals = {}
    try:
        for path, old, new in edits:
            f = ROOT / path
            originals.setdefault(f, f.read_text())
            text = f.read_text()
            if text.count(old) != 1:
                return "NOT APPLIED", f"expected exactly one match for {old[:60]!r} in {path}, found {text.count(old)}", []
            f.write_text(text.replace(old, new))
        for d in REPORT_DIRS:
            shutil.rmtree(d, ignore_errors=True)
        # The tools library is rebuilt first so the Loom tests see the sabotaged classes; each module then runs
        # whichever of the named test classes it has.
        out = ""
        build = subprocess.run(["mvn", "-B", "-o", "-q", "install", "-DskipTests", "-Djacoco.skip=true", "-Dmaven.test.skip.exec=true"],
                               cwd=TOOLS, capture_output=True, text=True, timeout=900)
        out += build.stdout + build.stderr
        if "COMPILATION ERROR" in out:
            return "COMPILE ERROR", "the sabotage doesn't compile: " + " ".join(l for l in out.splitlines() if "ERROR" in l and ".java" in l)[:300], []
        for module in (TOOLS, MODULE):
            proc = subprocess.run(["mvn", "-B", "-o", "-q", "test", f"-Dtest={classes}", "-DfailIfNoTests=false",
                                   "-Dsurefire.failIfNoSpecifiedTests=false", "-Djacoco.skip=true"],
                                  cwd=module, capture_output=True, text=True, timeout=900)
            out += proc.stdout + proc.stderr
        if not [x for d in REPORT_DIRS for x in d.glob("TEST-*.xml")]:
            return "NO REPORT", out[-400:], []
        tags = tagged_methods()
        failed = failing_methods()
        failed_tags = sorted({t for key in failed for t in tags.get(key, set())})
        names = sorted(f"{c.split('.')[-1]}.{m}" for c, m in failed)
        if not failed:
            return "SURVIVED", "no test failed", []
        missing = [t for t in expected if t not in failed_tags]
        return ("DETECTED" if not missing else "WEAK"), (f"failing checks {failed_tags}" + (f"; expected {missing} did not fail" if missing else "")), names
    finally:
        for f, text in originals.items():
            f.write_text(text)
        if originals:
            subprocess.run(["mvn", "-B", "-o", "-q", "install", "-DskipTests", "-Djacoco.skip=true"], cwd=TOOLS, capture_output=True, text=True, timeout=900)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    args = ap.parse_args()
    only = {s for s in args.only.split(",") if s}
    rows = []
    for sid, description, edits, classes, expected in SABOTAGES:
        if only and sid not in only:
            continue
        print(f"{sid}: {description} ...", flush=True)
        status, detail, names = run_one(sid, description, edits, classes, expected)
        print(f"   -> {status}: {detail}", flush=True)
        rows.append((sid, description, expected, status, detail, names))
    out = ["# G3: sabotage runs", "",
           "Each row breaks one rule in the real source, runs the tests that should notice, and restores the source.",
           "**DETECTED** means every expected check failed.", "",
           "| # | Sabotage | Expected to fail | Result | Failing checks | Failing tests |", "|---|---|---|---|---|---|"]
    for sid, desc, expected, status, detail, names in rows:
        shown = ", ".join(names[:6]) + (f" (+{len(names) - 6} more)" if len(names) > 6 else "")
        out.append(f"| {sid} | {desc} | {', '.join(expected)} | **{status}** | {detail} | {shown} |")
    evidence = SPEC / "evidence"
    evidence.mkdir(exist_ok=True)
    name = "G3-sabotage.md" if not only else "G3-sabotage-partial.md"
    (evidence / name).write_text("\n".join(out) + "\n")
    bad = [r for r in rows if r[3] != "DETECTED"]
    print(f"\n{len(rows) - len(bad)}/{len(rows)} detected" + ("" if not bad else "; NOT detected: " + ", ".join(f"{r[0]} ({r[3]})" for r in bad)))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
