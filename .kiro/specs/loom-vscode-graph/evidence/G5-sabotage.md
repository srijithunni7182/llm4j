# G5 sabotage run

Commit d154df7. Each row breaks one rule in the real sources, runs the checks that guard it, and records whether the named test failed. The sources are restored after every row.

| ID | Requirement | What was broken | Test that must fail | Result |
|---|---|---|---|---|
| S13 | VS.2 | Let the panel open any file it is asked to | opening a source file is allowed for the entry and its imports only | caught |

1 of 1 caught.
