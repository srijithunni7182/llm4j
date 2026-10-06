#!/usr/bin/env python3
"""G6 traceability for the Loom workflow graph. Mechanical checks only:

1. every numbered acceptance criterion in requirements.md is named by the traceability table in verification.md;
2. every check ID in that table exists in the verification matrix;
3. every check ID in the matrix is named by at least one test (an ID, or a range like "V1.1 to V1.5", in a test's
   name, comment or description).

    scripts/verify_graph_traceability.py      writes .kiro/specs/loom-vscode-graph/evidence/traceability.txt
"""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / ".kiro" / "specs" / "loom-vscode-graph"
TEST_ROOTS = [
    ROOT / "loom/ai-agent4j-loom/src/test", ROOT / "loom/graph-render/test", ROOT / "loom/vscode-loom/test",
    ROOT / "eval4j-report/src/test", ROOT / "scripts/verify-graph.sh", ROOT / "eval4j/src/test/java/io/github/llm4j/eval/export",
]
TEST_SUFFIXES = {".java", ".js", ".ts", ".sh", ".py"}


def criteria(requirements):
    out, current = set(), None
    for line in requirements.splitlines():
        m = re.match(r"### Requirement (\d+):", line)
        if m:
            current = int(m.group(1))
            continue
        m = re.match(r"(\d+)a?\.\s", line)
        if current and m and not line.startswith("    "):
            out.add((current, int(m.group(1))))
    return out


def table_ranges(verification):
    """The traceability table: 'N.a–N.b' ranges (or single numbers) per requirement."""
    covered = set()
    section = verification.split("## 7. Traceability", 1)[1].split("## 8.", 1)[0]
    for row in section.splitlines():
        m = re.match(r"\| (\d+)\.(\d+)(?:[–-](?:\d+\.)?(\d+))? \|", row)
        if m:
            req, lo = int(m.group(1)), int(m.group(2))
            hi = int(m.group(3)) if m.group(3) else lo
            covered |= {(req, i) for i in range(lo, hi + 1)}
    return covered, section


def matrix_ids(verification):
    return set(re.findall(r"^\| (V[A-Z]?\d*\.\d+[a-z]?) \|", verification, re.M))


def referenced_ids(section):
    ids = set()
    for token in re.findall(r"V[A-Z]?\d*\.\d+[a-z]?(?:[–-]V?[A-Z]?\d*\.?\d+)?", section):
        ids.add(token.split("–")[0].split("-")[0])
    return ids


def expand(token_text):
    """IDs named in a test source, with ranges such as 'V1.1 to V1.5' and 'V3.6, V3.7' expanded."""
    found = set(re.findall(r"\bV[A-Z]?\d*\.\d+[a-z]?\b", token_text))
    for m in re.finditer(r"\b(V)([A-Z]?\d*)\.(\d+)[a-z]? (?:to|and|–|-|through) V?(?:[A-Z]?\d*\.)?(\d+)", token_text):
        prefix, group, lo, hi = m.group(1), m.group(2), int(m.group(3)), int(m.group(4))
        found |= {f"{prefix}{group}.{i}" for i in range(lo, hi + 1)}
    return found


def tested_ids():
    found = set()
    for root in TEST_ROOTS:
        if not root.exists():
            continue
        for path in ([root] if root.is_file() else root.rglob("*")):
            if path.suffix in TEST_SUFFIXES and path.is_file() and "node_modules" not in path.parts:
                found |= expand(path.read_text(errors="ignore"))
    return found


def main():
    requirements = (SPEC / "requirements.md").read_text()
    verification = (SPEC / "verification.md").read_text()
    problems, lines = [], []

    want = criteria(requirements)
    have, section = table_ranges(verification)
    missing = sorted(want - have)
    lines.append(f"criteria in requirements.md: {len(want)}; named in the traceability table: {len(want & have)}")
    problems += [f"requirement {r}.{c} is not in the traceability table" for r, c in missing]

    known = matrix_ids(verification)
    for ref in sorted(referenced_ids(section)):
        base = re.sub(r"[a-z]$", "", ref)
        if ref not in known and base not in known and not re.match(r"V[A-Z]?\d*\.\d+$", ref):
            problems.append(f"{ref} is named in the traceability table but is not in the matrix")
    lines.append(f"check IDs in the matrix: {len(known)}")

    tests = tested_ids()
    untested = sorted(i for i in known if i not in tests and re.sub(r"[a-z]$", "", i) not in tests)
    lines.append(f"check IDs named by a test: {len(known) - len(untested)}/{len(known)}")
    problems += [f"{i} is in the matrix but no test names it" for i in untested]

    lines.append("")
    lines += ["PROBLEM: " + p for p in problems] or ["no gaps"]
    out = "\n".join(lines) + "\n"
    (SPEC / "evidence").mkdir(exist_ok=True)
    (SPEC / "evidence" / "traceability.txt").write_text(out)
    print(out)
    sys.exit(1 if problems else 0)


if __name__ == "__main__":
    main()
