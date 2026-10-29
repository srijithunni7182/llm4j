# Phase 22 design: CLI and language ergonomics (the small things that cost an afternoon)

Status: **draft for review**. Layer: the Loom lexer, parser, validator and test harness; the `weave` commands (`test`, `observe`, `review`, `fabric` new); the deployer skill's scripts; the review engine's cost estimate. Part of the [field findings program](../FIELD_FINDINGS.md); read its §2 and decision D3 in §5 first.
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Builds on [Phase 11](../11-workflow-tests/DESIGN.md) (`weave test`), [Phase 9](../09-review-engine/DESIGN.md) (review), [Phase 15](../15-embed-and-deploy/DESIGN.md) and [Phase 18](../18-install-and-identity/DESIGN.md).
Findings owned: **F43 to F54** (F51 and F52 are language and harness proposals with open questions; F50 waits on D3).

## 1. Problem

None of these is a wrong result. Each is a place where a developer who did the right thing was met with a flag that did not exist, a message that did not say what to write, a log line that buried the answer, or an error about the environment dressed as an error about the script. In the field session each cost minutes to an hour, and
each was **found by trial or by reading source**. The theme is the one the owner's QA guide uses for test tooling: *a failure should name the fix*. This phase is a list of fixes of that kind, kept apart from the P0 work so it can be built by different hands in parallel.

## 2. Goals and non-goals

Goals:

- `weave test`, `run` and `eval` take the same options for the same things (R1).
- A command's output is the answer, not the log (R2).
- A parse or check error that has a known fix **says the fix** (R3), and strings can hold a quote (R4).
- A variable is replaced only where the script says `{name}`, behind a decision (R5), and the lint covers every place that can be replaced.
- The harness and the CLI are honest about what they do and do not exercise (R7, R9, R11).
- Fabric and the deployer have one front door (R10).

Non-goals:

- Not new language power beyond what is named. R6 is a **proposal with a decision**, not a construct.
- Not performance work.
- Not the P0 behaviours (Phase 17) or the install story (Phase 18).

## 3. Requirements

**R1 `weave test --classes` and the same option set (F43).** `weave test` accepts `--classes <dir|jar>[:…]` (the project's task classes), `--loot <file>` and `--env-file`, the same names and meanings as `weave run` and `weave eval`. When none is given and the working folder is a Maven project with `target/classes`, `weave test` **uses it and says so in one line**; `--no-auto-classes` turns that off. The help of all three commands shares one text for these options (generated from one definition, so they cannot drift).

**R2 The output is the answer (F46).** The default log level for `weave` commands is **WARNING**; `-v`/`--verbose` gives INFO and `-vv` FINE. `LoomLoader`'s "Loading Loom script" line is FINE. A scripted case's header is printed only when the case fails, or with `-v`. A run's trace summary and `Result:` ([Phase 17 R5.3](../17-honest-results/DESIGN.md)) are what a person sees. Library users who configure their own logging are unaffected (the change is in the CLI's logging setup, not in the classes' log levels, except for the one line moved to FINE).

**R3 A known mistake says its fix (F47, F48).** A small **registry of known mistakes** maps a parse or check failure to a one-sentence hint, with golden-file tests, so adding one is a line of data and a test:

| Mistake | Today | The message becomes |
|---|---|---|
| `expecting {...}` after `retry N` | a parse error at the wrong token | "`expecting {…}` must come before `retry N` on a `delegate`; write `delegate "…" to A -> x expecting {…} retry 1`" |
| the word `value` as a key in a scripted task result map | a reserved-word error | "`value` is reserved in a scripted result; write the result as a plain string, or use another key such as `answer`" |
| `\"` inside a string | an unterminated or misread string | fixed by R4; before R4, "a string cannot contain a double quote; use a single quote or `"`" |
| `&&` or `||` in a condition | already has a hint (kept) | unchanged |
The registry is the place for every future "it said X but meant Y" and is documented in `CONTRIBUTING.md`.

**R4 A string can hold a double quote (F49).** The lexer today has **no escape handling**: `string()` reads to the next `"`. It gains `\"` (a double quote), `\\` (a backslash), and keeps `\n` and `\t` as they are (they are interpreted later, so existing scripts mean the same). A backslash followed by any other character is kept as written. A string that ends in a lone backslash before the closing quote is an error with a hint. **Compatibility:** a script that today ends a string with `\"` expecting the backslash to be part of the text would change; the lexer test corpus (every `.loom` file in the repository, `RepositoryScriptsTest`) shows whether any exists, and the release notes list the change.

**R5 Variables are replaced where braces say so (F50; decision D3).** The runtime replaces a variable's name **wherever the bare word appears** in a prompt, a question or a note (the documented "older bare-name form"); `WorkflowLint.wordsThatAreVariables` warns for payloads of `delegate`, `broadcast`, `human_prompt` and `note`, and missed `out` inside the text `length-out-of-range(...)` in the field session (a position it does not cover). Under D3's recommendation:
- **R5.1 Cover every position.** The lint is extended to **every string position the runtime substitutes**: the payloads of `delegate`, `broadcast`, `handoff`, `human_prompt`, `note`, `halt`, `set` values, `return`, task arguments, observe labels and expressions. The positions are listed **from the runtime's own calls** (the places `HarnessExecutor` calls `resolve`/`resolvePayload`, 18 of them today) so a new one cannot be forgotten: a test fails if the runtime substitutes in a position the lint does not scan.
- **R5.2 A script-level setting `substitution: braces`** makes only `{name}` and `{name.field}` replaced. The default stays `legacy` (today's behaviour) until the next major version (D3c), when it becomes `braces`; the release notes and `LOOM_GUIDE.md` say so now.
- **R5.3 A migration aid.** `weave check --substitution braces` (a preview) reports each string that would change under `braces`, with the variable and the position; `weave check --fix-substitution` rewrites a bare word that was meant as a variable to `{name}` after showing the diff, and leaves ambiguous cases for the person.

**R6 Branches that fall back, and a join (F51): a proposal with a decision.** Parallel branches that fall back by `set`ting a shared variable need a variable each (`bad_research`, `bad_writer`, …); a join would be cleaner. The cleanest answer is that the **runtime** records which steps used a fallback and exposes it ([Phase 17 R4.5](../17-honest-results/DESIGN.md), `_fallbacks`), so a script does not manage the flags at all. This phase specifies only: (a) the documentation of today's pattern in the guide, with the naming rule; (b) the **open question** of the read-only `_fallbacks` and how a condition reads a list (does `alt (_fallbacks contains "Researcher")` exist?). A language change needs its own short design and an owner decision; it is **not built here**.

**R7 Real task code inside a workflow test, safely (F52).** The harness scripts every task, so a task's real code is never exercised in a workflow test. A case may name `real: [CheckArticle, CheckOutput]`; for those tasks the harness runs the **actual class** instead of the scripted result. **Only tasks whose declared effect policy is `none`** (read-only and pure) may be real; naming a task that writes, sends or spends is an **error that says why** ("`SaveArticle` has effects; a test cannot run it for real; script its result"). The harness still scripts everything else.

**R8 `weave observe rules` works without ceremony (F44).** Without the inspect add-on on the class path the command says, in one sentence, what it is and how to get it ("the inspect add-on is not on the class path; `weave tools fetch inspect` ([Phase 18](../18-install-and-identity/DESIGN.md)) or add `loom-inspect` to `--classes`"). A missing or empty rules file means **no rules** (the defaults), not an error; `weave observe rules --init` writes a commented starter file.

**R9 Environment findings are not script findings (F45).** `weave review` reports a **missing API key** as an **environment finding**: it is shown in a separate "Setup" section (and in `weave check`/`doctor` as today), has severity **setup**, not HIGH, and is **excluded from `--fail-on` and from the review panel's open-findings count** unless `--include-setup` is given. The same holds for other states of the machine (no Java, no network) when a review rule would otherwise raise them. A test fixes the rule: **a finding is a script finding only if editing the script can clear it**.

**R10 One front door for Fabric and the deployer (F53).**
- **R10.1** `weave fabric up | down | status`: a thin command that finds the Fabric jar by [Phase 18's lookup order](../18-install-and-identity/DESIGN.md) and starts it as a **separate process** (no class dependency; the layering of "Loom knows nothing of Fabric" holds, as `weave` only launches a jar by path); `up` generates an admin password with owner-only permissions, binds loopback (refusing an unsafe bind with the existing message), prints the address and where the password file is **without printing the password**; `status` shows each client with **when it last reported** (the one-line answer to "is my process reporting?"); `down` stops it.
- **R10.2** `run-observed` and `weave run --observe-fabric` accept `--secrets-key-file <file>` (and the environment variable), so a master key kept elsewhere needs no symlink named `<store>.key`.
- **R10.3** The deployer skill's scripts become thin wrappers: they call `weave fabric …` when `weave` is available and keep their present logic only as the fallback, with the same lookup order. The skill text says so.

**R11 The cost estimate says what it assumed (F54).** `weave review`'s cost block states **its assumptions** (calls per agent and tokens per call, typical and worst case, searches), prints **dollars** whenever a price is known and names the model whose price is missing otherwise, and, when the worst case is at or above the run's cap, says so in one sentence ("the cap, 150,000 tokens, will stop the run before the worst case, 179,000: expect cut results"). When `preflight.json` ([Phase 20](../20-guided-path-and-skills/DESIGN.md)) or a recorded run holds **measured** usage for an agent, the estimate **uses it** (the existing `--calibrate` path, now fed automatically) and labels it measured.

## 4. Compatibility

| Change | Existing users |
|---|---|
| R1 | New options; auto-detected classes are announced in one line and can be turned off. |
| R2 | Less output by default; `-v` restores it. A script that parsed `INFO:` lines from `weave` output must pass `-v` (release note). |
| R3 | Messages change; nothing is accepted or rejected differently. |
| R4 | `\"` now means a quote inside a string. A script that relied on it ending a string would change (checked against the repository's scripts; listed in the release notes). |
| R5 | Default behaviour unchanged until the next major version; the new setting, lint and preview are opt-in. |
| R7 | `real:` is new; scripted cases are unaffected. |
| R8, R9 | Fewer false errors; `--include-setup` restores the old counting. |
| R10 | New command; the scripts keep working as before when `weave` is absent. |
| R11 | The cost block has more lines; JSON adds fields. |

## 5. Security notes

- R7 never runs a task with effects for real; the policy is read from the task's declaration and an undeclared task is treated as having effects (fail closed).
- R10.1: the admin password file is created owner-only (mode 0600) and never printed or logged; the launcher passes it by file, not by argument ([the repository's secret rules](../../../CLAUDE.md)).
- R3's hints never echo the text of a prompt, a key or a value, only names and positions.
- R5.3's rewrite is shown as a diff first and never touches a file without a yes.

## 6. Open questions

1. R5 and D3: is `braces` the right new default at the next major version for **every** string position, or should prompt files (`prompts/*.md`) keep their own placeholder rules? Proposed: one rule everywhere, documented once.
2. R6: should the language gain `alt (_fallbacks contains "X")`, or a more general list condition? It touches the expression grammar and deserves its own design.
3. R7: is the effect policy of every built-in and custom task declared today? The inventory in §0 settles whether "undeclared means effects" would block common tasks.
4. R10.1: should `weave fabric up` start Fabric under the user's service manager for a deployment, or only for local use? Proposed: local use only; deployment is `weave deploy`'s job.
5. R2: should `weave` honour `LOOM_LOG` or `-Djava.util.logging.config.file` as today? Proposed: yes, they win over the default.

## 7. Documentation affected

The generated pages for `weave test/run/eval/observe/review/fabric/check`; `LOOM_GUIDE.md` (strings, `substitution`, `expecting`/`retry` order, the parallel fallback pattern, `real:` in test cases); `CONTRIBUTING.md` (the known-mistakes registry); the deployer skill; the release notes (R2, R4, R5 notes); `docs/API_COMPATIBILITY.md`; `LOOM_PROMPT.md`; `docs/guide/06-build-the-workflow.md`; the `llm4j-workflow-guide` skill; the VS Code grammar and snippets (a string with `\"`, the `substitution` setting); the CTK scenarios for R4 and R5.
