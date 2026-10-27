# From an incident to a regression test

A run went wrong in production. You want it to be a test, and you want every later change checked against what really happened.

## Capture a run

    weave capture --list --since 14d          # runs worth capturing: failed, diverted, unsure, stepped in on
    weave capture RUN_ID --store .loom-triggers --note "refund loop"

The case is written to `tests/captured/` beside the script. It is a normal test (`weave test` runs it) with a `captured:` block saying where it came from. It holds the inputs, what the models and classifiers answered, what people answered and which steps failed, **masked**: emails, phone numbers, card numbers, keys and passwords are replaced by stand-ins of the same shape. Names and words no pattern can find are masked only if you give them (`--mask-term "Maria Gonzalez"`). **Read every case before you commit it.** The first capture in a repository asks you to agree to what it does; `capture.properties` limits what may be captured (`workflows`, `deny-fields`, `max-age`).

A new case is `observed`: it records what happened, which may have been wrong. Read it, then `weave capture --approve FILE` (or `--amend final=...` when what happened was the bug). Only approved cases fail a gate.

A run that failed on an agent call nothing handled, or a run suspended for a person, can be captured. A run with `parallel` or `broadcast` steps cannot yet.

## Check a change against real history

    weave regress main.loom --baseline-git HEAD~1 --store .loom-triggers --limit 200 --sample stratified

Each sampled run is replayed from its journal through your candidate script, with no model call. The report counts runs that behave the same, take another route, return another result, or **need a model** (the candidate changes what a model is asked; it is never guessed). Anything that adds an effect or takes a person out of the path is listed first. `--models live --max-cost 1.00 --prices prices.properties` spends up to a cap to compare those runs with real models, and says what was spent.

## Make it a gate

    weave gate main.loom --base-ref origin/main --format md

Stages (`check`, `review`, `test`, `regress`, `eval`) come from `gate.properties`; a stage with no data (no run store in CI) is skipped and shown as skipped. `--print-workflow github` prints a starting CI file.

## From Fabric

Start Fabric with `--allow-capture --capture-store DIR` and sign in. **Create test** appears on the run page and on every incident (with "N tests created from this kind of incident"), and **Try a change** on the run page. The case is a download: Fabric never writes into a source repository. When the journal is on another machine, the page shows the `weave capture ...` command to run there. Every use is in Fabric's audit trail (who, which run, when), never the content.

## Privacy and retention

- Capture is a safeguard, not a guarantee: patterns plus the terms you give.
- Nothing is deleted by the tool; captured text lives in git. Limit it with `capture.properties`.
- The approval records who and when (git email, else the OS user). It is a review aid, not a security control.
