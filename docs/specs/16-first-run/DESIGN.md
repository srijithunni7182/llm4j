# Phase 16 design: the first run (`weave try`, `weave doctor`, one-line install, the guided path)

Status: **draft for review**. Layer: Loom CLI (`ai-agent4j-loom`: commands `weave try`, `weave doctor`, an improved `weave init` and `weave next`), distribution (a JBang catalog entry, a launcher), the extension's welcome view. Companion documents:
[TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [program overview](../LOOM_DX.md). Builds on [Phase 11](../11-workflow-tests/DESIGN.md) (scripted collaborators: the "demo models"), [Phase 12](../12-generate/DESIGN.md) (`weave generate`),
[Phase 9](../09-review-engine/DESIGN.md), `weave init` templates, `weave next` (`ProjectAdvisor`), `weave guide`, the Fabric simulator (`src/examples/fabric-sim`), `weave run --trace`, `scripts/doctor.sh` (a *contributor* tool for stale jars; a different thing), `weave package`.

## 1. Problem

Every phase before this one improves a developer who already has a workflow. The first ten minutes decide whether anyone gets that far. Today a newcomer must:

1. Know there is a CLI (`weave`) and get it: build or download a JAR, run `java -jar weave.jar ...` (or install the VS Code extension, which bundles it).
2. Have an API key for a paid model provider before seeing anything run (the examples need keys; `--mock` shows `[mock answer]`, which proves wiring but is not satisfying).
3. Choose among `weave init` templates, read the guide, and understand agents vs tasks vs classifiers before the first "it works".
4. Diagnose a failure with no way to tell whether it is Java, the key, the network, the model name, the proxy or the script.

The repo is full of the right ideas for experts. The goal here is the opposite end: **a developer who has Java and nothing else sees a real workflow run, traced, in under two minutes, with no key and no account, and knows exactly what to do next.**

## 2. Goals and non-goals

Goals:

- **`weave try`**: run a bundled, realistic workflow on **demo models** (scripted, deterministic, instant, free) with a live narrated trace, the graph, a cost estimate, a failure, a human step, and a pointer to the next step. No key, no network, no files left behind unless asked.
- **`weave doctor`**: one command that checks the environment and says what is wrong *and the one command or setting that fixes it*: Java version, the CLI version vs the extension's, keys present for the models a script uses (names only, never values), reachability of each provider endpoint (opt-in), proxy settings, write access to the run store, a stale prices file, Fabric reachability.
- **One-line install** on any machine with Java: a JBang alias (`jbang weave@srijithunni7182/llm4j ...`) and a documented download-and-run for those without JBang; a launcher script `weave` so the command is `weave`, not `java -jar`.
- **A guided path**: `weave init` with no arguments asks one question and either offers a template or calls `weave generate`; `weave next` always knows the next step and says it in one line; the VS Code welcome view walks the same path.
- **Time to first success is a measured number**, checked by a scripted "newcomer" test in CI.
- **No telemetry, no account, no network by default** — stated, tested.

Non-goals:

- Not a tutorial site or video content (docs work is listed, not specified here).
- Not a package-manager presence on every platform (Homebrew, apt, Scoop, SDKMAN are named as follow-ups; JBang plus a direct download is the v1).
- Not an installer for Java.
- Not live provider demos in `try` (the point is no key; `try --live` is allowed later for those who have one, §3.4).

## 3. `weave try`

```
weave try [scenario] [options]
  (no argument)         pick the default scenario and run it
  --list                list scenarios with one-line descriptions and what each demonstrates
  --step                pause between steps (press Enter), like a guided tour
  --fast                no pauses or pacing, for demos in scripts
  --keep <dir>          write the scenario project to <dir> so you can open and edit it (default: nothing is written)
  --fabric              also start the Fabric simulator page and print its local address
  --live                run with the models you have configured (needs keys; see doctor) instead of demo models
  --no-color
```

### 3.1 Scenarios (R1 to R4)

| Scenario | Shows | Contains |
|---|---|---|
| `triage` (default) | A support message routed by a classifier, a reply by an agent, a person asked when unsure | `intentclassify`, `alt`, `delegate`, `human_prompt`, `return` |
| `newsletter` | A research → write → edit pipeline with a loop | `delegate`, `loop until … max`, `output_schema`, budgets |
| `refund` | Money, safely: a deterministic policy task decides, a person approves, an effect is simulated | `run` task, `approve`, `decision`/`ladder` intro |
| `flaky` | Things going wrong: a tool fails twice, retry and `on_failure` take over | `retry`, `timeout`, `on_failure`, rate-limit pause |

- **R1** Scenarios are real Loom projects (the repo's examples, curated and trimmed) shipped inside the JAR, each with a **demo script** — a phase 11 test file whose scripted collaborators produce a realistic, varied run: believable model replies, a classifier answer, a failure, a human answer.
  `try` runs the project **through the real interpreter** with those collaborators; nothing about the language is faked.
- **R2** The run is **narrated**: for each step, one line saying what Loom is doing and why that is interesting ("`alt` — the condition is evaluated by Loom, not by a model: `intent.kind == "order_status"` is true"), then the result. The narration is generated from the phase 13 decision events plus a small text table per statement kind, so it is accurate by construction and cannot drift from the language.
- **R3** The run ends with a **summary** (the phase 9 card: findings, cost estimate for a real run and for this one: "this demo cost $0"), the **graph** (ASCII, always; with `--fabric` also the live page), and **three next commands** specific to the scenario: `weave try --keep ./demo`, `weave test`, `weave generate "…"`.
- **R4** Every scenario's demo script also serves as its **test suite** (phase 11), so `try`'s fixtures are verified by the build and `weave try --keep dir` leaves a project whose `weave test` passes.

### 3.2 No keys, no network, no residue (R5 to R8)

- **R5** `try` makes **no network call**, reads no environment variable except `NO_COLOR`/terminal width, and needs no key. A test runs it with the network namespace empty and `HOME` read-only.
- **R6** `try` writes **nothing** unless `--keep` is given (a temp directory in memory-backed storage if the scenario needs files, removed on exit, including on interrupt).
- **R7** `try` states plainly at the start and the end that the models were scripted ("demo models: replies are pre-written so this works offline; real models vary"), so nobody believes a demo is a benchmark.
- **R8** `Ctrl+C` exits cleanly with a one-line message; a closed pipe or a non-terminal output degrades to plain lines without colour or pacing.

### 3.3 Speed (R9)

- **R9** The default `triage` scenario finishes in under 5 s from process start on CI hardware (JVM start included), under 2 s of that for the run itself with pacing off (`--fast`). With pacing, the whole tour is about 30 s and is interruptible.

### 3.4 `--live` (R10, R11)

- **R10** `--live` runs the same scenario with the developer's configured models and keys, **with a spend cap of $0.05 by default**, prints the estimate before starting and asks to continue, and reuses `weave doctor`'s findings if a key is missing ("set GEMINI_API_KEY, or choose another model with --model").
- **R11** It is the only path in `try` that can reach the network, and says so before it starts.

## 4. `weave doctor`

```
weave doctor [script.loom] [--network] [--fix] [--format text|json]
```

Checks, each with status `ok | warn | fail | skipped`, a one-line reason, and the **single next action**:

| Check | Looks at | Fix text |
|---|---|---|
| Java | version ≥ 17 (the baseline), vendor, `JAVA_HOME` vs `PATH` mismatch | "Install a JDK 17+ (`sdk install java`, or https://adoptium.net), or set `loom.javaPath`" |
| CLI | `weave` version, jar location, **the extension's bundled jar version** vs the CLI on PATH (the repo's own stale-jar trap, for users) | "Update the CLI: `jbang app install --force …`" |
| Project | a `.loom` found / given; `weave check --no-env` result; prompts present | "run `weave check` for the details" |
| Keys | the `env.NAME` and provider keys the script needs; present/absent (**names only**, never a value, never a prefix); `.env`/`.env.example` rules (reusing `EnvExampleCheck`) | "export GEMINI_API_KEY=… or add it to .env; see `weave secret`" |
| Models | the model names in the script exist in the known model catalog; one-sentence note when a model name is a likely typo | "did you mean `claude-haiku-5-5`?" |
| Network (only with `--network`) | a TLS handshake to each provider endpoint the script uses; proxy variables; the corporate-proxy hints; **no key is sent** (a connect/TLS check, not an API call) | "your proxy needs `HTTPS_PROXY`; the CA bundle…" |
| Store | the run store directory is writable and has space; a `weave daemon` lock is not stale | "remove `<lock>` or use `--store`" |
| Prices & windows | the price file exists and is newer than N days; windows catalog present | "update prices.properties; costs are estimates until you do" |
| Fabric | if `--observe-fabric` is configured, health and version match, bind safety | "start Fabric: …" |
| Extension | (inside VS Code) engine running, protocol versions match (phase 10) | "reload the window" |

- **R12** Output ends with a one-line verdict ("Ready: 8 ok, 1 warning" / "Not ready: 2 failures — fix #1 first") and exits 0 / 1 / 2 (ready / warnings only treated as ready / failures). `--format json` is versioned for the extension's status view.
- **R13** `doctor` **never prints, logs or transmits a secret value**, a key prefix, a token fragment or the length of a secret. A test seeds secrets and greps all output.
- **R14** `--network` is the only mode that makes connections; each is a TLS connect to the provider's documented host with a short timeout, no request body, no credentials, and the list of hosts is printed first. Without `--network`, `doctor` is entirely local.
- **R15** `--fix` applies only changes that are safe and local and **asks first**: create the run store directory, write a `.gitignore` entry for `.env`, copy `.env.example` to `.env`. It never edits shell profiles, installs software or changes system settings.
- **R16** Every failure text is one sentence plus one command or setting. A test checks each message against that rule (length and the presence of an action).
- **R17** `scripts/doctor.sh` (contributor stale-jar tool) is unchanged and linked from CONTRIBUTING with the distinction.

## 5. Install (R18 to R22)

- **R18** **JBang**: a catalog entry (`jbang-catalog.json` at the repository root, with the `weave` alias pointing at the released Maven Central artifact/fat jar) so that `jbang weave@srijithunni7182/llm4j try` works on any machine with JBang, and `jbang app install weave@srijithunni7182/llm4j` puts `weave` on PATH.
- **R19** **Direct**: the release publishes `weave.jar` (fat) plus a `weave` launcher script for POSIX and `weave.cmd` for Windows (they locate Java, check the version with a clear message, and exec `java -jar`), with a checksum file; the docs show a three-line install. The launcher passes arguments untouched and honours `JAVA_OPTS`.
- **R20** **Version coherence**: the launcher, the jar and the extension report one version; `weave --version` prints it and the protocol version of `serve`; the extension warns when its bundled engine and a CLI on PATH differ in major protocol.
- **R21** The launchers are tested (shellcheck, a Windows batch test where Windows is the host, and an execution test with a fake Java).
- **R22** Package-manager formulae (Homebrew tap, SDKMAN candidate, Scoop manifest) are **specified as follow-ups** with their checklists in the docs; this phase delivers JBang and the launcher only.

## 6. The guided path (R23 to R28)

### 6.1 `weave init` with no arguments

```
$ weave init
What do you want to build?  (a sentence, or press Enter to pick a template)
> route support tickets and reply to the simple ones

I can generate this for you (needs a model key), or start from a template.
  1) Generate it      weave generate "route support tickets and reply to the simple ones"
  2) Start from the 'classifier' template
  3) Look at a demo first      weave try
```

- **R23** `init` with no arguments is interactive on a terminal; with no terminal it prints the usage and the template list exactly as today (scripts are not broken).
- **R24** If no key is configured, option 1 explains it needs one and shows `weave doctor`; options 2 and 3 work without one.
- **R25** Templates gain `tests/` (phase 11) that pass, a `review.properties` stub, and a README whose steps are the guided path below; `weave init` output is verified by running `weave check`, `weave review` (no HIGH), and `weave test` on every template in the build.

### 6.2 `weave next` as the spine

- **R26** `weave next` already orders the steps (valid → tests → coverage → limits → reach → free wiring proof → spend). It gains the phase 9–14 stages in order: **review → test → coverage → (eval, optional) → deploy preflight → capture/regress once runs exist**, prints exactly one *primary* next command and at most two alternatives, and says why in a sentence. It stays free (no model, no key).
- **R27** Every command's last line suggests the next one where it is obvious (`check` passes → "weave review"; `review` clean → "weave test"; `test` passes → "weave run --mock"; `run --mock` ok → "set your key, then weave run --max-cost 0.05"), and every suggestion is the same one `weave next` would give (a test checks they agree).

### 6.3 The editor welcome

- **R28** In VS Code, a `.loom`-less workspace shows a **welcome view** with three buttons — *Try a demo* (runs `weave try --step` in the integrated terminal), *Generate a workflow* (phase 12), *Open an example* — and a *Check my setup* button (`weave doctor`). With a `.loom` open, the status bar item from phase 10 links to `weave next`'s primary action. Welcome content is declared in the manifest (`viewsWelcome`), tested by the manifest test.

## 7. Time to first success (R29 to R31)

- **R29** Target: with Java installed and nothing else, **`jbang weave@… try` → narrated run complete in under 2 minutes of wall time including JBang's download** (network-bound, measured manually), and under 10 s of CPU time once the jar is present. Reported in VERIFICATION as measured numbers, not asserted in the build because the download is outside our control.
- **R30** A scripted **newcomer test** (`NewcomerJourneyTest`) runs the documented path with no keys and no network: `weave try --fast` → `weave try --keep` → `weave check` → `weave review` → `weave test` → `weave run --mock` → `weave next`; asserts exit codes, that each step prints the next command, that the total number of commands typed is ≤ 6, and that total CPU time is within the budget.
- **R31** Documentation is checked by tests the way the repo already does (`ContextDocsExamplesTest`): the "Getting started" page's commands are extracted and executed in the newcomer test, so the docs cannot drift from the product.

## 8. Principles specific to this phase

- **Honest demos.** Scripted models are labelled as scripted every time (R7). A demo that implied real-model behaviour would be marketing, not onboarding.
- **Never block on an account.** Nothing in this phase asks for, creates or implies an account or sign-in.
- **Failure messages are product.** Every error `try` and `doctor` can print follows one rule: what happened, why it matters, the one thing to do (R16). A message with no action is a bug.
- **Small, fast, quiet.** No banners, no emoji walls, no spinners that hide logs; plain text first, colour as enhancement (`NO_COLOR` respected), accessible output (no information carried by colour alone).

## 9. Architecture

```
try/       Scenario (project + demo script), Scenarios (registry, from resources), Narrator (decision events + statement table), Pacer, AsciiGraph, TryCommand
doctor/    Check interface, checks (Java, Cli, Project, Keys, Models, Network, Store, Prices, Fabric, Extension), Report, Fixes, DoctorCommand
install/   launcher scripts (sh, cmd), jbang-catalog.json, checksum
init/      InteractiveInit (existing templates + generate hand-off)
advisor/   ProjectAdvisor extended with phases 9–14 stages (existing class)
```

- **R32** `try` has no code path that constructs a provider client in demo mode (a test fails if a provider class is loaded during `try` without `--live`).
- **R33** The narrator and the scenarios are data-driven (statement kind → sentence template; scenario resources), so adding a scenario is adding a folder, not code.

## 10. Sub-phases

| | Delivers | Tests |
|---|---|---|
| 16a | Scenario packaging (triage + flaky), demo scripts as phase 11 suites, `weave try` (run, narrate, summary, graph, `--keep`, `--fast`), no-network/no-residue guarantees | `TryRunsRealInterpreterTest`, `TryNoNetworkTest`, `TryNoResidueTest`, `NarratorTest`, `ScenarioSuitesPassTest` |
| 16b | `weave doctor` (local checks, messages rule, JSON, `--fix`), `--network` TLS check, secrets safety | `DoctorChecksTest`, `DoctorNoSecretsTest`, `DoctorMessageRuleTest`, `DoctorNetworkOptInTest`, `DoctorFixAsksFirstTest` |
| 16c | Launchers, JBang catalog, version coherence, checksums | `LauncherTest`, `JbangCatalogTest`, `VersionCoherenceTest` |
| 16d | `init` interactive, templates with tests/review, `weave next` extended and agreement test, remaining scenarios (newsletter, refund) | `InitInteractiveTest`, `TemplatesPassGatesTest`, `NextAgreesWithHintsTest` |
| 16e | Editor welcome view, newcomer journey test, docs-as-tests, `--live` with cap | `welcomeView.test.ts` (manifest), `NewcomerJourneyTest`, `GettingStartedDocsTest`, `TryLiveCapTest` |

## 11. Decisions to confirm (open questions)

1. **JBang as the primary install.** It needs JBang itself (one more thing to install), but works the same on every OS and fetches from Maven Central. Alternative: a direct jar plus launcher only. Recommendation: both, JBang documented first.
2. **Which scenario is default.** `triage` (classifier + human, short) vs `newsletter` (a pipeline most people recognise). Recommendation: `triage`; it shows what is distinctive about Loom (symbolic routing, a human step) in 30 seconds.
3. **Narration tone.** Plain and short vs friendly. Recommendation: plain, one line per step, no jokes; consistent with the rest of the documentation.
4. **Jar size.** Bundling scenarios and the narrator adds a few hundred KB. Confirm acceptable; the extension bundles the jar too.
5. **`weave doctor --network`** reveals which provider hosts a project uses to the network (a TLS connect). It is opt-in and prints the hosts first. Confirm acceptable.
