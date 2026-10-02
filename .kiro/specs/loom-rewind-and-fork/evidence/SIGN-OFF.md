# Sign-off: Loom rewind and fork

Base commit fa50f17 (evidence files carry the commit they were produced at; the code under test is unchanged since G1).

| Gate | Result | Evidence |
|---|---|---|
| G0 | every task ticked; tree clean | tasks.md |
| G1 | three clean builds green; plain runs have equal counts (core 657, tools 381, loom 492); third run in random order with 2000 fuzz iterations and 1500 random workflows | G1-build.txt |
| G2 | all 64 checks traced to a passing tagged test; none disabled | G2-traceability.txt |
| G3 | 12 of 12 sabotage mutations detected (the first run found two surviving mutations and two that did not compile; tests were added for the first and the mutations fixed for the second) | G3-sabotage.md |
| G4 | 1123 baseline tests, none gone, none newly failing; 97 added | G4-regression.txt |
| G5 | six real-process scenarios against stand-in model and webhook servers: two rewinds and publish; kill -9 mid-rewind then resume (2 rewinds, none repeated, 1 Slack post); kill -9 after the Slack post then resume (1 post, 0 repeated); operator rewind held then `--effects keep` (post found, not repeated); simulated fork (0 posts); fork under another script with and without drift; `reset --failed` after an outage | g5/R1–R6 |
| G6 | packaged JAR checks a script with checkpoint and rewind and lists the four commands | G6-package.txt |
| G7 | guide blocks validated, commands exist, hover text present | G7-docs.md |
| G8 | JaCoCo gates met: Generations 96.6%, RunTravel 94.2%, OverlayJournal 100%, EffectScan 97.1% branches; Rewinder 96.7% lines | G8-coverage.txt |
| G9 | V8 checks pass; secrets sweep clean; security review found nothing at confidence 8 or above | G9-safety.md |

## Recorded deviations from the design
- A boundary stores the block and the index of the first statement, not a step-id string.
- The boundary list is one versioned journal entry (`{version, boundaries}`); another version is refused.
- `reset --failed` marks failed steps with journal kind `retry`.
- Tools of an unclassified kind are recorded (`#unclassified:`) only in scripts that use a rewind.
- Named stop points exist (`<step>#decide-proposal` is reserved for earned autonomy); `--trigger` leaves a resume trigger; the run lock is `run.lock`; operator actions go to `operator-audit.jsonl`.
- A rewind condition must be bracketed and a single comparison.
- Gate scripts: `scripts/verify-rewind.sh` (G1, G2, G4, G6, G8), `scripts/sabotage_rewind.py` (G3), `scripts/g5/run_rewind.sh` (G5).
