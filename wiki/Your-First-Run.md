# Your first run

You need Java 17 or newer. No key, no account, no network.

    jbang weave@srijithunni7182/llm4j try        # or: weave try, with the launcher from install/README.md

`weave try` runs a real workflow with the real interpreter on **demo models**: replies written in advance, so it works offline and costs nothing (it says so at the start and the end; real models vary). Each step gets one sentence: what Loom did and why. Scenarios: `triage`, `flaky`, `newsletter` (`weave try --list`). `--step` pauses at each step; `--keep ./demo` leaves the project to edit; `--live` runs it with your own models under a token cap, after telling you it goes to the network and asking.

Then:

    weave check ./demo/main.loom --no-env
    weave review ./demo/main.loom --no-env
    weave test ./demo/main.loom
    weave next ./demo

`weave next` gives the next step in order: review, tests, coverage, a deploy preview, the first real capped run, and, once runs exist, turning one into a test.

Something wrong? `weave doctor` checks Java, the script, the keys it needs (by name only), model names (with the nearest correct one), the run store, prices and Fabric, and says the one thing to do for each. `--network` also tests a TLS connection to each provider host (no request, no key). `--fix` does the safe local fixes after asking.

`weave init` with no arguments, on a terminal, asks what you want to build and offers generating it, a starter template, or the demo. In VS Code, a folder with no `.loom` file shows the same four choices in the Loom view.
