#!/usr/bin/env python3
"""Lists the mutants a PIT run did not kill, and the score.

    scripts/pit_survivors.py target/pit-reports/mutations.xml [--since REF] [--min=85] [--class=Name ...]

--since REF  judge only the lines that changed since REF (git diff), so a change to a big old class is judged on what it changed
--class=Name judge only classes with this simple name (repeatable)
Exit status 1 when the score is below --min.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def changed_lines(ref):
    """{ 'File.java': {line, ...} } for the lines added or changed since ref."""
    out = subprocess.run(["git", "diff", "-U0", ref, "--", "*.java"], capture_output=True, text=True, check=True).stdout
    lines, current = {}, None
    for row in out.splitlines():
        if row.startswith("+++ b/"):
            current = row[6:].rsplit("/", 1)[-1]
            lines.setdefault(current, set())
        elif row.startswith("@@") and current:
            m = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", row)
            start, count = int(m.group(1)), int(m.group(2) or 1)
            lines[current].update(range(start, start + count))
    return lines


def main(argv):
    report, since, minimum, classes = None, None, 85.0, set()
    it = iter(argv[1:])
    for a in it:
        if a == "--since":
            since = next(it)
        elif a.startswith("--min="):
            minimum = float(a.split("=", 1)[1])
        elif a.startswith("--class="):
            classes.add(a.split("=", 1)[1])
        else:
            report = a
    only = changed_lines(since) if since else None
    total = killed = 0
    for m in ET.parse(report).getroot().findall("mutation"):
        cls = m.find("mutatedClass").text.rsplit(".", 1)[-1].split("$")[0]
        line = int(m.find("lineNumber").text)
        source = m.find("sourceFile").text
        if classes and cls not in classes:
            continue
        if only is not None and line not in only.get(source, set()):
            continue
        total += 1
        if m.get("status") in ("KILLED", "TIMED_OUT"):
            killed += 1
        else:
            print(m.get("status"), cls, line, m.find("mutatedMethod").text, "|", m.find("mutator").text.rsplit(".", 1)[-1], "|", m.find("description").text[:100])
    score = 100.0 * killed / total if total else 100.0
    print(f"{killed}/{total} killed ({score:.0f}%)")
    return 0 if score >= minimum else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
