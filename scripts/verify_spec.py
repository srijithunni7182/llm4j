#!/usr/bin/env python3
"""Traceability for a spec: every check id in its verification.md has a test tagged with it that ran and passed, no test carries an id the
spec doesn't have, nothing is disabled and every test asserts something.

    verify_spec.py <spec-dir> <tag-prefix>        e.g.  verify_spec.py .kiro/specs/loom-rewind-and-fork RW
Tests carry their check as @Tag("RW-V2.1"). Test sources and surefire reports are read from every module that has them.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MODULES = [ROOT / "src" / "ai-agent4j", ROOT / "src" / "ai-agent4j-tools", ROOT / "src" / "loom" / "ai-agent4j-loom"]

spec = ROOT / sys.argv[1]
prefix = sys.argv[2]
ID = r"V\d+\.\d+"
text = (spec / "verification.md").read_text()
live_section = text.split("## Live checks")[1] if "## Live checks" in text else ""
rows = text.split("## Live checks")[0]
ids = set(re.findall(rf"^\| ({ID}) \|", rows, re.M))
gated = set(re.findall(rf"^\| ({ID}) \|.*\(gate-checked: G\d+\)", rows, re.M))   # proved by a gate script, not a unit test
ids -= gated

tag_re = re.compile(rf'@(?:[\w.]*\.)?Tag\("{prefix}-({ID})"\)')
tagged = {}   # (class, method) -> {ids}
sources = []
for module in MODULES:
    for f in sorted((module / "src" / "test" / "java").rglob("*.java")):
        src = f.read_text()
        if f"Tag(\"{prefix}-" not in src:
            continue
        sources.append((f, src))
        pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
        for m in re.finditer(r"((?:\s*@[\w.]+(?:\([^)]*\))?)+)\s*(?:public |private |protected )?(?:static )?void (\w+)\(", src):
            found = set(tag_re.findall(m.group(1)))
            if found:
                tagged[(f"{pkg}.{f.stem}", m.group(2))] = found

status = {}
for module in MODULES:
    for xml in (module / "target" / "surefire-reports").glob("TEST-*.xml"):
        for case in ET.parse(xml).getroot().iter("testcase"):
            name = re.sub(r"\(.*$|\[.*$", "", case.get("name"))
            key = (case.get("classname"), name)
            bad = case.find("failure") is not None or case.find("error") is not None
            now = "failed" if bad else ("skipped" if case.find("skipped") is not None else "passed")
            prev = status.get(key)
            status[key] = now if prev in (None, "passed") else prev

passing = {}
for key, found in tagged.items():
    for i in found:
        passing.setdefault(i, []).append(status.get(key, "not run"))

problems = []
missing = sorted(i for i in ids if i not in passing)
for i in missing:
    problems.append(f"check {prefix}-{i} has no test")
for i in sorted(ids):
    if i in passing and "passed" not in passing[i]:
        problems.append(f"check {prefix}-{i} has tests but none passed: {passing[i]}")
unknown = sorted({i for found in tagged.values() for i in found} - ids)
for i in unknown:
    problems.append(f"a test is tagged {prefix}-{i}, which is not a check in verification.md")
def blanked(src):
    """The source with the inside of string literals, text blocks and character literals replaced by spaces (same length), so braces in them are not counted."""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append("".join(c if c == "\n" else " " for c in src[i:j]))
            i = j
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i))
            i = j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join(c if c == "\n" else " " for c in src[i:j]))
            i = j
        elif src[i] == '"' or src[i] == "'":
            q, j = src[i], i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == "\\" else 1
            out.append(" " * (j + 1 - i))
            i = j + 1
        else:
            out.append(src[i])
            i += 1
    return "".join(out)


for f, src in sources:
    clean = blanked(src)
    if "@Disabled" in src:
        problems.append(f"{f.name} has @Disabled")
    for m in re.finditer(r"@(?:Test|ParameterizedTest|RepeatedTest)[^\n]*\n(?:\s*@[^\n]*\n)*\s*(?:public |private )?void (\w+)\([^)]*\)[^{]*\{", src):
        depth, i = 1, m.end()
        while depth and i < len(clean):
            depth += {"{": 1, "}": -1}.get(clean[i], 0)
            i += 1
        if not any(h in src[m.end():i] for h in ("assert", "Fuzz.run", "fail(", "crashAtEveryWrite", "run(")):
            problems.append(f"{f.name}: {m.group(1)} contains no assertion")

req = (spec / "requirements.md").read_text()
matrix = text.split("## Requirement to check")[1] if "## Requirement to check" in text else ""
for n in sorted(set(re.findall(r"^### Requirement (\d+):", req, re.M)), key=int):
    if not re.search(rf"\| R{n} ", matrix):
        problems.append(f"requirement {n} is not in the requirement-to-check table")

print(f"checks in verification.md: {len(ids)}; with at least one passing test: {len(ids) - len([p for p in problems if p.startswith('check')])}")
print(f"tagged test methods: {len(tagged)}; executed: {sum(1 for k in tagged if k in status)}")
if problems:
    print("\nPROBLEMS:")
    for p in problems:
        print("  -", p)
    sys.exit(1)
print("\ntraceability: OK")
