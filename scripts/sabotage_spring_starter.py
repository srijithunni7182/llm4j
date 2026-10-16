#!/usr/bin/env python3
"""Gate G5 for the Spring Boot starter: the tests can fail. Breaks one rule at a time in the real sources (starter and LoomHost), runs the starter and
host tests, and checks that tests tagged with the expected checks fail. Sources are restored afterwards, whatever happens.

    scripts/sabotage_spring_starter.py [--only S1,S7]      writes .kiro/specs/spring-boot-starter/evidence/G5-sabotage.md
"""
import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
STARTER = ROOT / "src" / "spring" / "llm4j-spring-boot-starter"
LOOM = ROOT / "src" / "loom" / "ai-agent4j-loom"
SPEC = ROOT / ".kiro" / "specs" / "spring-boot-starter"
S = "src/spring/llm4j-spring-boot-starter/src/main/java/io/github/llm4j/spring/"
H = "src/loom/ai-agent4j-loom/src/main/java/io/github/llm4j/loom/host/"
TESTS = "io.github.llm4j.spring.*Test,io.github.llm4j.loom.host.*Test"

SABOTAGES = [
    ("S1", "drop @ConditionalOnMissingBean from the primary LLMClient",
     [(S + "Llm4jClientsAutoConfiguration.java", "    @ConditionalOnMissingBean(LLMClient.class)\n    @Conditional(Llm4jConditions.ClientsConfigured.class)\n    public LLMClient llmClient(",
       "    @Conditional(Llm4jConditions.ClientsConfigured.class)\n    public LLMClient llmClient(")], ["V0.3"]),
    ("S2", "accept a client with no provider instead of failing at start-up",
     [(S + "ClientFactory.java", "if (c.provider() == null || c.provider().isBlank()) {", "if (false) {")], ["V3.1"]),
    ("S3", "let the literal api-key win over api-key-secret",
     [(S + "CredentialProperty.java", "if (hasLiteral && hasSecret) {", "if (false) {"), (S + "CredentialProperty.java", "        if (hasSecret) {\n            if (store == null) {", "        if (hasSecret && !hasLiteral) {\n            if (store == null) {")], ["V2.8"]),
    ("S4", "resolve api-key-secret once at start-up",
     [(S + "CredentialProperty.java", "return SecretRef.of(store, secretName);", "return SecretRef.literal(store.resolve(secretName));")], ["V2.6"]),
    ("S5", "never mask personal data",
     [(S + "Llm4jClientsAutoConfiguration.java", "if (properties.privacy().maskPii()) {", "if (false) {")], ["V4.7"]),
    ("S6", "ignore the per-call cap",
     [(S + "Llm4jClientsAutoConfiguration.java", "if (properties.budget().perCallMaxTokens() != null) b.perCallCap(properties.budget().perCallMaxTokens());", "")], ["V4.2"]),
    ("S7", "skip kind.check before create",
     [(S + "GuardedTools.java", "String problem = kind.check(options, baseDir);", "String problem = null;")], ["V6.2"]),
    ("S8", "let a later tool overwrite an earlier one of the same name",
     [(S + "ToolCatalog.java", "if (earlier != null) {", "if (false) {")], ["V6.6"]),
    ("S9", "remove the durability guard",
     [(H + "LoomHost.java", "DurabilityGuard.check(script, persistence.durable(), b.allowNonDurable);", "")], ["V8.28"]),
    ("S10", "ignore fail-on",
     [(S + "Llm4jLoomAutoConfiguration.java", "if (report.failsAt(threshold)) {", "if (false) {")], ["V9.5"]),
    ("S12", "accept an answer for a step that is not waiting",
     [(H + "LoomHost.java", 'if (q == null) return new AnswerResult(false, "run " + runId + " is not waiting at " + stepId);', "")], ["V8.12"]),
    ("S13", "never shut an executor down after a run ends",
     [(H + "LoomHost.java", "            if (ex != null) closeExecutor(ex);\n            live.remove(runId);", "            live.remove(runId);")], ["V8.5"]),
    ("S14", "attach observe after initialize()",
     [(H + "LoomHost.java", "        if (o.observe != null) {\n            if (o.scriptFile != null) o.observe.attach(ex.observable(), o.scriptFile);\n            else o.observe.attach(ex.observable());\n        }\n        if (o.customizer != null) o.customizer.accept(ex);\n        ex.initialize();\n        return ex;",
       "        if (o.customizer != null) o.customizer.accept(ex);\n        ex.initialize();\n        if (o.observe != null) {\n            if (o.scriptFile != null) o.observe.attach(ex.observable(), o.scriptFile);\n            else o.observe.attach(ex.observable());\n        }\n        return ex;")], ["V12.4"]),
    ("S15", "give run executors no trigger store",
     [(H + "LoomHost.java", "ex.setTriggerStore(persistence.triggers());", "")], ["V8.14"]),
    ("S16", "use the system clock instead of the injected one in executors",
     [(H + "LoomHost.java", "        ex.setClock(clock);", "")], ["V8.14"]),
    ("S19", "make the agent builder a singleton",
     [(S + "Llm4jAgentsAutoConfiguration.java", "    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)\n    @ConditionalOnMissingBean\n    @ConditionalOnBean(LLMClient.class)", "    @ConditionalOnMissingBean\n    @ConditionalOnBean(LLMClient.class)")], ["V5.6", "V5.7"]),
    ("S20", "print the literal api-key while creating a client",
     [(S + "ClientFactory.java", '        String where = "llm4j.clients." + name;', '        String where = "llm4j.clients." + name;\n        System.out.println("creating client with key " + c.apiKey());')], ["V2.10"]),
]


def reports():
    return [STARTER / "target" / "surefire-reports", LOOM / "target" / "surefire-reports"]


def failing():
    out = set()
    for d in reports():
        for xml in d.glob("TEST-*.xml"):
            for case in ET.parse(xml).getroot().iter("testcase"):
                if case.find("failure") is not None or case.find("error") is not None:
                    out.add((case.get("classname"), re.sub(r"\(.*$|\[.*$", "", case.get("name"))))
    return out


def tags():
    out = {}
    for module in (STARTER, LOOM):
        for f in (module / "src/test/java").rglob("*.java"):
            src = f.read_text()
            if 'Tag("SB-' not in src:
                continue
            pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
            for m in re.finditer(r"((?:\s*@[\w.]+(?:\([^)]*\))?)+)\s*(?:public |private |protected )?(?:static )?void (\w+)\(", src):
                found = set(re.findall(r'@Tag\("SB-(V\d+\.\d+)"\)', m.group(1)))
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
        for d in reports():
            for x in d.glob("TEST-*.xml"):
                x.unlink()
        proc = subprocess.run(["mvn", "-B", "-q", "test", "-pl", "src/spring/llm4j-spring-boot-starter", "-am", f"-Dtest={TESTS}", "-Dsurefire.failIfNoSpecifiedTests=false",
                               "-DfailIfNoTests=false", "-Djacoco.skip=true"], cwd=ROOT, capture_output=True, text=True, timeout=2400)
        out = proc.stdout + proc.stderr
        if "COMPILATION ERROR" in out:
            return "COMPILE ERROR", " ".join(l for l in out.splitlines() if "ERROR" in l and ".java" in l)[:300], []
        if not any(list(d.glob("TEST-*.xml")) for d in reports()):
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
    out = ["# G5: sabotage runs", "", "Each row breaks one rule in the real source, runs the starter and host tests, and restores the source.",
           "**DETECTED** means every expected check failed.", "",
           "| # | Sabotage | Expected to fail | Result | Failing checks | Failing tests |", "|---|---|---|---|---|---|"]
    for sid, desc, expected, status, detail, names in rows:
        shown = ", ".join(names[:6]) + (f" (+{len(names) - 6} more)" if len(names) > 6 else "")
        out.append(f"| {sid} | {desc} | {', '.join(expected)} | **{status}** | {detail} | {shown} |")
    evidence = SPEC / "evidence"
    evidence.mkdir(exist_ok=True)
    (evidence / ("G5-sabotage.md" if not only else "G5-sabotage-partial.md")).write_text("\n".join(out) + "\n")
    bad = [r for r in rows if r[3] != "DETECTED"]
    print(f"\n{len(rows) - len(bad)}/{len(rows)} detected" + ("" if not bad else "; NOT detected: " + ", ".join(f"{r[0]} ({r[3]})" for r in bad)))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
