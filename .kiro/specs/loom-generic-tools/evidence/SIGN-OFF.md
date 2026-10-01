# Sign-off

Code under test: **6e3de87**, after the tools moved into the `ai-agent4j-tools` library (tools: 372 tests, Loom: 402). Gates were run by the implementer (no independent verifier was available) and the
safety review was a separate `/security-review` pass. Commits after 6e3de87 change only spec and evidence files.

| Gate | Evidence | SHA | Result | Date |
|---|---|---|---|---|
| G0 Preconditions | G0-preconditions.txt | see file | Pass | 2026-10-01 |
| G1 Clean build, three runs | G1-build.txt | 6e3de87 | Pass | 2026-10-01 |
| G2 Traceability | G2-traceability.txt | 6e3de87 | Pass (113 of 113 checks) | 2026-10-01 |
| G3 Sabotage | G3-sabotage.md | 6e3de87 (re-run after the move to ai-agent4j-tools) | Pass (23 of 23 detected) | 2026-10-01 |
| G4 Regression | G4-regression.txt | 6e3de87 | Pass | 2026-10-01 |
| G5 Real runs | G5-runs.md | 6e3de87 (re-run after the move to ai-agent4j-tools) | Pass (stand-in model and services) | 2026-10-01 |
| G6 Packaged app | G6-package.txt | 6e3de87 | Pass | 2026-10-01 |
| G7 Docs and tooling | G7-docs.md | 6e3de87 | Pass, except the editor was only syntax-checked | 2026-10-01 |
| G8 Coverage and evidence | G8-coverage.txt | 6e3de87 | Pass | 2026-10-01 |
| G9 Safety review | G9-safety.md | 6e3de87 (re-run after the move to ai-agent4j-tools) | Pass (two findings fixed) | 2026-10-01 |
| G10 Live (optional) | | | **Not run**: no accounts | |

G3 and G5 were re-run on the new layout (the sabotage driver now rebuilds the tools library before running the tests of both modules).

**Per-tool completion**

| | `webhook` | `email` | `http` | `file` | `shell` | `sql` |
|---|---|---|---|---|---|---|
| Its V-group passes (V4 / V5 / V6 / V7 / V8 / V9) | x | x | x | x | x | x |
| Its hostile attacks (H1 / H3 / H2 / H4 / H5 / H6) | x | x | x | x | x | x |
| Its sabotage rows fail as expected | x | x | x | x | x | x |
| Real run by hand (R3) | x | x | x | x | x | x |
| Runs from the packaged JAR (G6) | x | x | x | x | x | x |
| Documented, with an example that parses (G7) | x | x | x | x | x | x |
| Secrets sweep clean (G9b) | x | x | x | x | x | x |
| Effect-journal replay verified (V2) | x | x | x | x | x | n/a |

Known gaps: live checks L1–L4 (a real model, Slack, SMTP, PostgreSQL), `triggers install --apply`, the editor's language
server, and the Teams body shape. The "runs from the packaged JAR" row for `webhook`, `http`, `file` and `email` rests on
`weave check` of the digest from the JAR; for `shell` and `sql` on the same check of a script declaring both. A full
`weave run` from the JAR was not done; R1–R5 ran from the compiled classes.
