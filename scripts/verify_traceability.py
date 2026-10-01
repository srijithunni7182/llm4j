#!/usr/bin/env python3
"""Gate G2: every check in verification.md has an executed, passing test; no test carries an unknown ID;
every requirement is in the traceability matrix; nothing is disabled; every test asserts something."""
import re
import sys

from loomgen import SPEC, TEST_DIR, check_ids, results, tagged_methods, test_files

problems = []
ids = check_ids()
tags = tagged_methods()
status = results()

# 1. Checks -> tests (executed and passing)
passing = {}
for key, tag_set in tags.items():
    for t in tag_set:
        passing.setdefault(t, []).append(status.get(key, "not run"))
missing = sorted(i for i in ids if i not in passing)
unexecuted = sorted(i for i in ids if i in passing and "passed" not in passing[i])
print(f"checks in verification.md: {len(ids)}; with at least one test: {len(ids) - len(missing)}")
for i in missing:
    problems.append(f"check {i} has no test")
for i in unexecuted:
    problems.append(f"check {i} has tests but none passed: {passing[i]}")

# 2. Tests -> checks (no typos hiding a gap)
unknown = sorted({t for tag_set in tags.values() for t in tag_set} - ids)
for t in unknown:
    problems.append(f"a test is tagged {t}, which is not a check in verification.md")

# 3. Requirement criteria -> matrix
req = (SPEC / "requirements.md").read_text()
matrix = (SPEC / "test-strategy.md").read_text().split("## 9. Traceability")[1].split("## 10.")[0]
for n in sorted(set(re.findall(r"^### Requirement (\d+):", req, re.M)), key=int):
    if not re.search(rf"\bR{n}\b|\bR{n}\.\d+", matrix):
        problems.append(f"requirement {n} is not in the traceability matrix")
for k in range(1, 12):
    if f"R1.{k} " not in matrix and f"R1.{k}\n" not in matrix and f"R1.{k}|" not in matrix:
        problems.append(f"requirement 1.{k} is not in the traceability matrix")

# 4. No disabled tests
for f in test_files():
    if "@Disabled" in f.read_text():
        problems.append(f"{f.name} has @Disabled")

# 5. Every @Test method asserts something (directly, or through a helper that does)
HELPERS = ("assert", "attack(", "Fuzz.run", "verify(", "fail(")
for f in test_files():
    src = f.read_text()
    for m in re.finditer(r"@(?:Test|ParameterizedTest|RepeatedTest)[^\n]*\n(?:\s*@[^\n]*\n)*\s*(?:public |private )?void (\w+)\([^)]*\)[^{]*\{", src):
        depth, i = 1, m.end()
        while depth and i < len(src):
            depth += {"{": 1, "}": -1}.get(src[i], 0)
            i += 1
        body = src[m.end():i]
        if not any(h in body for h in HELPERS):
            problems.append(f"{f.name}: {m.group(1)} contains no assertion")

print("test tags: ", len(tags), "tagged methods;", "executed:", sum(1 for k in tags if k in status))
if problems:
    print("\nPROBLEMS:")
    print("\n".join("  - " + p for p in problems))
    sys.exit(1)
print("G2 traceability: OK")
