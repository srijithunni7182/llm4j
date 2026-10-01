"""Shared helpers for verifying the Loom generic tools: which test carries which check ID, and what ran."""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC = ROOT / ".kiro" / "specs" / "loom-generic-tools"
MODULE = ROOT / "loom" / "ai-agent4j-loom"
TEST_DIR = MODULE / "src" / "test" / "java" / "io" / "github" / "llm4j" / "loom" / "generic"
REPORTS = MODULE / "target" / "surefire-reports"
ID = r"(?:V\d+\.\d+|F\d+|H\d+|C\d+)"


def check_ids():
    """Every check ID in a table row of verification.md (live checks excluded)."""
    text = (SPEC / "verification.md").read_text()
    return set(re.findall(rf"^\| ({ID}) \|", text, re.M))


def test_files():
    return sorted(TEST_DIR.rglob("*.java")) + [MODULE / "src/test/java/io/github/llm4j/loom/tools/SafePathsTest.java",
                                                    MODULE / "src/test/java/io/github/llm4j/loom/execution/ApprovalKeyCompatTest.java"]


def tagged_methods():
    """{(fully qualified class, method): {check IDs}} from @Tag annotations in the test sources."""
    out = {}
    for f in test_files():
        src = f.read_text()
        pkg = re.search(r"^package ([\w.]+);", src, re.M).group(1)
        cls = f.stem
        # annotations (possibly several @Tag, @Test, ...) followed by a method declaration
        for m in re.finditer(r"((?:\s*@[\w.]+(?:\([^)]*\))?)+)\s*(?:public |private |protected )?(?:static )?void (\w+)\(", src):
            tags = set(re.findall(rf'@Tag\("({ID})"\)', m.group(1)))
            if tags:
                out[(f"{pkg}.{cls}", m.group(2))] = tags
    return out


def results():
    """{(class, method): 'passed' | 'failed' | 'skipped'} from the surefire XML reports (parameter suffixes removed)."""
    out = {}
    for xml in REPORTS.glob("TEST-*.xml"):
        for case in ET.parse(xml).getroot().iter("testcase"):
            name = re.sub(r"\(.*$|\[.*$", "", case.get("name"))
            status = "failed" if case.find("failure") is not None or case.find("error") is not None else (
                "skipped" if case.find("skipped") is not None else "passed")
            key = (case.get("classname"), name)
            # a method run several times (parameterised, repeated) passes only if every run did
            prev = out.get(key)
            out[key] = status if prev in (None, "passed") else prev
            if prev == "skipped" and status == "passed":
                out[key] = "passed"
    return out
