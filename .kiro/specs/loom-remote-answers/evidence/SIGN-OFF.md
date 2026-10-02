# Sign-off: Loom remote answers

Builds on the earned-autonomy sign-off (commit 95732b2; gate G0a). Evidence files carry the commit they were produced at; the code under test has not changed since G1 except for documentation and evidence.

| Gate | Result | Evidence |
|---|---|---|
| G0 | every task ticked except the optional live checks (task 11); tree clean | tasks.md |
| G0a | the earned-autonomy gates passed at 95732b2, which this work builds on | ../../loom-earned-autonomy/evidence/SIGN-OFF.md |
| G1 | three clean builds green; equal counts in the plain runs (core 657, tools 381, loom 686); the third in random order | G1-build.txt |
| G2 | all 43 checks traced to a passing tagged test (54 tagged methods, all executed) | G2-traceability.txt |
| G3 | 10 of 10 sabotage mutations detected (the first run missed three: one test was strengthened so the allowed sender comes last, one so two questions are open at once in a fixed order, and one mutation was redefined because the first version was equivalent) | G3-sabotage.md |
| G4 | 1354 baseline tests, none gone, none newly failing; 54 added; no existing test edited | G4-regression.txt |
| G5 | real `weave` processes against a stand-in Telegram server and a stand-in model: a decision asked on the phone with no terminal (plain text, no proposal shown), reply, one tick resumes it; a stranger's reply ignored, the daemon killed with `kill -9`, a second daemon carries on and the answer applies once; `weave answer` from a terminal and a second answer refused; an expired question closes and the run carries on; the token appears nowhere | g5/B1–B5 |
| G6 | packaged JAR checks a script with a question, lists `answer`, `questions`, `tick`, `daemon`; `questions` on an empty store and `answer` on an unknown code behave; `--ask-via` without `--journal` stops at start | G6-package.txt |
| G7 | guide section validated against the code, commands and options exist, the `channel.json` example parses | G7-docs.md |
| G8 | JaCoCo rules enforced in the build and met: Answers 94% and Listener 87% branches, PendingStore 87% lines, channel package at least 85% lines | G8-coverage.txt |
| G9 | V5 checks pass; secrets sweep clean; a focused security review; one real bug found by G5 and fixed (stale replies) | G9-safety.md |
| G10 | not run: needs a real bot and an always-on machine | LIVE-CHECK.md (a checklist for you to run) |

## Recorded deviations from the design
- Codes are six characters (about 29.7 bits), not five (24.8 bits, below the 25-bit requirement).
- `Channel.send` takes the target chat as an argument and `Channel.poll` returns a batch with a cursor that is acknowledged after the replies are recorded (the spec sketched one send and one poll).
- Answers are audited to `<store>/channel/audit.jsonl` (and kept in the pending record), not in the run's own audit log.
- Replies older than their question are dropped (added after the G5 run found the need): by Telegram message number, else by date with a minute's allowance.
- A store with questions but no channel wraps the console so that an answer recorded with `weave answer` is returned to the resumed run (found by G5: without it a console resume would block).
- An expired question hands the step an empty answer; for a `decide` the second ask then goes out under a new code, as it would at a console that returned nothing.
- Gate scripts: `scripts/verify-remote.sh` (G1, G2, G4, G6, G8), `scripts/sabotage_remote.py` (G3), `scripts/g5/run_remote.sh` (G5).
