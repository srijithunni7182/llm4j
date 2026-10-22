# Phase 14 design: the production loop (`capture`, `regress`, the gate)

Status: **draft for review**. Layer: Loom (`ai-agent4j-loom`: package `loop`; commands `weave capture`, `weave regress`, `weave gate`), `loom-fabric` (an action on incidents and run pages), CI integration. Companion documents:
[TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md), [program overview](../LOOM_DX.md). Builds on [Phase 11](../11-workflow-tests/DESIGN.md) (test files, scripted collaborators),
[Phase 13](../13-time-travel/DESIGN.md) (observe-replay, `whatif`, overlay), [Phase 9](../09-review-engine/DESIGN.md) (review, cost), the run journal, `weave eval` golden datasets, `weave replay` (decisions), Fabric incidents ([phase 4](../04-inspect-and-incidents/DESIGN.md)), and the PII guards.

## 1. Problem

Everything in phases 9 to 13 helps a developer *before* a workflow ships. Most of what a team learns about a workflow arrives *after*, from real inputs: a customer message nobody imagined, a tool that started returning a new shape,
a classifier that is confident and wrong. Today the path from that discovery to a safeguard is manual and lossy:

1. Fabric shows an incident (a loop, a failing tool, a `BROKEN` run, a suspected injection).
2. The developer reproduces it by hand (if they can: the input may have been in a journal that is gone, the model reply is not repeatable).
3. They write a test or a golden case from memory.
4. They change a prompt or a script and hope nothing else regressed, because the only inputs they have to try are the ones they invent.

Loom holds exactly what is needed to close this loop: **every run is journaled** (inputs, every model reply, every tool result, every human answer), and the language is total, so a past run can be re-derived and re-run against a changed script with effects simulated.
No framework that only wraps API calls can do this, because it never recorded the run. This phase turns that into three things a team can use in a minute: **capture** a real run as a regression test; **regress** a candidate change against real history; **gate** a change in CI.

## 2. Goals and non-goals

Goals:

- **`weave capture`**: turn a journaled run (or an incident) into a phase 11 test case whose scripted collaborators *are* the recorded ones, so the test reproduces what production did, deterministically, with no model.
- **`weave regress`**: run a candidate script/prompt/model over a *sample of real past runs* and report what changed: routes, results, effects, cost, latency — with unsafe changes marked.
- **`weave gate`**: a CI-ready wrapper that fails a change which flips approved behaviour, with thresholds a team sets in a file.
- **Fabric**: an incident or run page offers "Create test from this run" and "Try a change on this run".
- **Privacy by default**: captured data is masked, secrets never captured, retention and consent are explicit.
- **Trust states**: a captured case is *observed* (what happened) until a person marks it *approved* (what should happen), and only approved cases can fail a gate.
- **Zero cost for the logic case**: when a change affects control flow but not model inputs, regress needs no model calls (phase 13's recorded-model reuse).

Non-goals:

- Not learning: no fine-tuning, no automatic prompt rewriting from captured runs (the guide's prompt optimization stage can *use* the cases; this phase does not rewrite prompts).
- Not a data warehouse: captured cases are test fixtures in the repo; fleet-wide analytics stay in Fabric.
- Not guaranteeing equivalence under model change: when a candidate changes model inputs, regress says what it could and could not check (§5.3).
- Not collecting data anywhere but the developer's own filesystem. No upload, no telemetry.

## 3. `weave capture` (R1 to R14)

```
weave capture <run-dir|run-id|incident-id> [options]
  --store <dir>                   where runs are kept
  --to <dir>                      test folder (default: tests/captured/ beside the script)
  --name <slug>                   case name (default: from workflow + short hash + date)
  --as test|golden|both           test: a phase 11 case with scripted collaborators (default);
                                  golden: an eval dataset entry (input + optional reference); both
  --expect observed|none          fill expectations from what happened (default) or leave them empty
  --mask pii|none|<rules>         masking before writing (default: pii)
  --keep-text                     keep model/tool text as recorded after masking; default keeps it (needed to replay), see R6
  --note "<why this was captured>"
  --tag <t>…
  --from-incident <id>            capture the run behind a Fabric incident and tag the case with its kind
  --dry-run                       print the case, write nothing
```

### 3.1 What a captured case contains

```yaml
# tests/captured/ticket-triage-4f2a-2026-10-14.test.yaml  (generated; edit freely)
captured:
  from: run r18f3a2c-9b1
  script: triage.loom
  scriptHash: sha256:9c1e…       # the version that ran
  at: 2026-10-14T10:42:03Z
  by: weave capture 6.2.0
  status: observed               # observed | approved
  note: "customer said 'parcel never arrived' and was routed to the queue; should be a person"
  masked: [ email, phone ]       # what was masked, never the values
cases:
  - name: captured r18f3a2c-9b1
    tags: [ captured, incident:routing ]
    input: { message: "my parcel never arrived. email me at ‹email›" }
    models:
      Intent: { kind: other, urgent: "no", tone: annoyed, confidence: 0.81 }     # exactly what the classifier said
      Replier: [ "…recorded reply, masked…" ]
    tools: { }
    tasks: { }
    humans: [ ]
    expect:
      final: completed
      route: [ intentclassify Intent, return ]
      result: { team: other, route: queue }
```

- **R1** The case is a **valid phase 11 test file** (`weave test --check` passes) and, run immediately, **passes** against the script version that produced it (the capture verifies this before writing; if it does not, capture fails and explains the divergence, e.g. the script changed since).
- **R2** Inputs, model replies (agents and classifiers, typed), tool results, task results (for tasks with reach beyond `none`; pure tasks are left real), human answers and failures/retries (as `fail:` entries reproducing the attempt pattern) are taken from the journal and trace; nothing is invented.
- **R3** `expect` is filled from the observed outcome (`route`, `result`, `final`, `calls`) and the case is marked `status: observed`. A person approves it with `weave capture --approve <file> [--edit]` after reading it; approval records who and when (`approvedBy` from the OS user or `--by`) and may amend expectations (the *correct* behaviour is often not the observed one).
- **R4** `--expect none` writes a case with no expectations beyond completion, for the "I want to see what happens when I change this" use.
- **R5** `--as golden` writes an `eval` dataset entry (`id`, `name`, `input`, `context`, `dimensions`, `rubric` left as a TODO the developer fills, `tags: { source: captured }`), for quality evaluation with real models; the input is masked the same way.
- **R6** **Masking.** Before anything is written, values pass through the PII masker already in the stack (`guard { pii: mask }` rules) plus a secret scrubber (key-shaped strings, `env.` values, `Authorization` headers, URLs with credentials). Masking is **format-preserving where a downstream condition might depend on shape** (an email stays shaped like an email, a number like a number) so the replayed route stays valid. A masked placeholder is stable within a case (`‹email1›`).
  Masking runs *before* the case is displayed or written, and a test proves no raw value reaches disk.
- **R7** If masking changes a value a condition depends on, the capture re-verifies (R1) after masking; if the route changed, it **fails with the field named**, so a case is never silently wrong. The developer may supply `--mask-keep <field>` (and take responsibility) or edit the case.
- **R8** Secrets and credentials in a journal are never copied, whatever the flags. A test seeds secrets of many shapes into a journal and greps the output.
- **R9** The captured run's **untrusted content is marked**: a case whose input or tool results were flagged as suspected injection by Fabric is tagged `injection` and its masked text is kept as data, never executed or interpolated into any generated prompt.
- **R10** Naming and placement are deterministic (`<workflow>-<runhash4>-<date>`), re-capturing the same run is idempotent (same file, no duplicate), and two captures never overwrite a hand-edited file without a diff and confirmation.
- **R11** A run still in progress or suspended can be captured **up to its stop point** (`final: suspended`, with `humans` filled so far), which turns a stuck production run into a test for the fix.
- **R12** **Retention and consent.** The tool prints, on first use in a repository, what a captured case contains and that it will be committed to source control, and requires `--yes` or an interactive confirmation once per repository (recorded in `.weave/capture.consent`). A `capture.properties` can restrict what may be captured (`workflows = Support`, `deny-fields = ssn,card`, `max-age = 30d`).
- **R13** `weave capture --list [--store …] [--incident …] [--since …]` shows candidates (runs with incidents, failures, `BROKEN`/`DIVERTED`, low-confidence, human-overridden decisions), so a developer picks what to capture.
- **R14** Batch: `--select failed|diverted|unsure|overridden|slow|costly --limit N` captures the N most recent of a class, each as its own case, to bootstrap a suite from production in one command.

## 4. Approval and the trust of a case (R15 to R17)

| State | Meaning | Can fail a gate? |
|---|---|---|
| `observed` | This is what happened. It may have been wrong. | Only with `--include-observed` (off by default) |
| `approved` | A person confirmed (or corrected) the expected behaviour | Yes |
| `retired` | No longer applicable (workflow changed on purpose) | No |

- **R15** `weave capture --approve|--retire <file>` changes the state, records who/when/why, and refuses to approve a case that does not currently pass unless `--expect` was amended in the same step (approving a failing case is declaring the script wrong, which belongs in a visible commit).
- **R16** `weave test` runs all cases (observed and approved); `weave gate` counts only approved. A summary says how many cases are awaiting approval, so observed cases do not accumulate silently (`weave next` also reports it).
- **R17** Case files live in the repository and are reviewed like code; a CI check (`weave test --require-approved`) can demand that no `observed` case is merged.

## 5. `weave regress` (R18 to R30)

```
weave regress <candidate.loom> [options]
  --baseline <script.loom>        the script that produced the runs (default: the candidate's git HEAD version via --baseline-git, else the script hash recorded in the runs)
  --store <dir>                   where past runs are kept
  --since <14d|date>  --limit <n>  --sample random|recent|stratified|failures-first   (default: stratified by route, seeded)
  --select <filter>               only runs matching: workflow, route node, result field, tag, incident kind
  --models recorded|live|scripted     recorded (default) / live (capped) / scripted (from a file)
  --prompt <Agent>=<file|@v>…  --model <Agent>=<id>…    changes to try without editing files
  --effects simulate              (only mode; regress never performs effects)
  --max-cost <usd> --max-tokens <n>   required with --models live
  --judge <none|exact|label|rubric:<file>|classifier:<name>>   how to compare answer text (default: exact for typed fields, none for free text)
  --fail-on flip-unsafe|flip-any|cost-up:<pct>|none     exit 1 on this (default: flip-unsafe)
  --format text|json|md|junit   --report <file.html>
```

### 5.1 What is compared

For each sampled run, the candidate is run forward from the start (observe-replay for unchanged prefix; then forward execution where inputs differ) with recorded or scripted model replies and simulated effects. The comparison per run:

| Compared | How |
|---|---|
| Route | Ordered steps that ran; first divergence |
| Result | Typed fields exactly; text fields by `--judge` |
| Effects | The set of simulated effects (kind, target, payload digest) |
| Branches taken | `alt` outcomes (the phase 13 decisions) |
| Cost and tokens | Original (recorded) vs candidate (recorded reuse = same; estimated for changed calls, labelled) |
| Latency | Recorded vs estimated from the path (labelled estimate) |
| Failures | Handler paths taken |

### 5.2 The report

```
Regress  triage.loom (candidate) vs triage@9c1e (baseline)   200 runs sampled from 14 days (stratified by route, seed 7)

  Same            171  (85.5 %)
  Changed route    24  ( 12 %)    18 toward a person, 4 toward the queue, 2 toward an automatic reply
  Changed result    3
  Needs a model     2  (the candidate's prompt changes Replier's input; not guessed — use --models live)

  UNSAFE FLIPS      2
    r18f3a2c-9b1   queue → automatic reply    NEW EFFECT: reply sent to the customer without a person     [captured: approved]
    r90bd4e07-2e   person → automatic reply   ...

  Cost per run     $0.0021 → $0.0024  (+14 %, estimated for 31 changed calls)
  Time on path     same
```

- **R18** **Unsafe flip** = a change from a path with fewer/no effects (or with a person) to a path with a new effect or without a human step (a new `sends`/`writes` effect, a removed `approve`/`human_prompt`/`decide` stage, a rung climb in a ladder). Unsafe flips are listed first and drive `--fail-on flip-unsafe`.
- **R19** **Needs a model.** A run in which the candidate's model input differs from the recorded input cannot be compared under `recorded` (R26 of phase 13); it is counted and listed, never guessed. With `--models live` and a cap it is run, and the report separates *recorded-comparable* from *live-compared* runs so the reader knows what each number means.
- **R20** **Sampling is honest.** `stratified` samples across routes/results/incident kinds so rare-but-important paths are not drowned; the report states the population, the sample, the seed and the strata, and extrapolates nothing: "24 of 200 sampled changed" never becomes "12 % of production" unless the sample is `random` (and then states a confidence interval).
- **R21** **Approved captured cases are always included** in addition to the sample, and a flip on one is marked `[approved]` and fails the gate regardless of the sample.
- **R22** **No effects, ever.** Regress never calls a tool with an effect, never sends, never writes, never touches the run store (read-only), and verifies the store's hash before and after. A `--effects real` flag does not exist.
- **R23** **Privacy.** Reports contain run ids, routes, field names and counts by default; message text only with `--show-text`, and then masked per R6.
- **R24** **Judging free text.** `--judge rubric:<file>` and `classifier:<name>` (phase 7's classifier-backed judges) compare free text with a model and therefore cost money: they require `--max-cost` and are off by default; `exact` and `label` are free.
- **R25** **Cost honesty.** Changed-call costs use the phase 9 estimator on the candidate's prompts and the recorded token ratio, labelled *estimated*; live-called costs are measured.
- **R26** **Baseline.** `--baseline` defaults to the script hash recorded with each run (`RunSpec`), loaded from git history when available (`--baseline-git`), so the comparison is always against *what actually ran*; runs whose script version cannot be recovered are excluded and counted.
- **R27** **Speed.** 200 runs with recorded models and no live calls complete in under 30 s on CI hardware (observe-replay is cheap); parallel by default.
- **R28** The JSON report (version 1) lists per-run outcomes so the editor/Fabric can drill into one (`weave whatif --save` reproduces any row).
- **R29** `--report file.html` is a single self-contained file (no remote resources, text via `textContent`-safe templating) for review in a pull request.
- **R30** Exit codes: `0` nothing at or above `--fail-on`; `1` a failing condition; `2` could not run (no runs found, store unreadable); `4` stopped by `--max-cost`.

### 5.3 What regress cannot tell you

Stated in every report and in the docs: it does not know how a *changed* prompt makes a real model behave (only `--models live` on a sample, or `weave eval`, does); it only sees inputs that happened; recorded model replies are one sample of a non-deterministic function; effects are simulated, so downstream systems' reactions are unknown.

## 6. `weave gate` (R31 to R35)

`weave gate` is the CI command, chosen by a team on purpose and never implied by running or deploying (phase 9 R49): it runs, in order, the checks a team has configured, prints one verdict, and exits non-zero on failure.

```
weave gate [script.loom] [--config gate.properties] [--candidate-ref HEAD] [--base-ref origin/main]
```

```properties
# gate.properties  (beside the script)
check        = true                  # weave check --strict
review       = fail-on:high          # phase 9
test         = true                  # weave test (phase 11), --require-approved
test.min-coverage = 80
regress      = true
regress.store = /var/loom/runs       # or a path/URL the CI job can read; "off" to skip
regress.since = 14d
regress.limit = 200
regress.fail-on = flip-unsafe
regress.max-cost = 0.50              # only used for --models live
eval         = false                 # weave eval (real models, spends money; usually nightly)
```

- **R31** Each stage reports pass/fail/skipped with its own summary; the verdict lists *which stage failed and the one command to reproduce it locally*.
- **R32** Stages that need data not present in CI (no run store) are **skipped with a visible note**, not silently passed, and `--require regress` turns a skip into a failure.
- **R33** `--candidate-ref/--base-ref` (git refs) makes the baseline the script at the base ref and the candidate the script at the candidate ref, which is the natural PR check; the changed files (script, prompts, tools, loot) are listed.
- **R34** Output formats: text, markdown (for a PR comment, `--format md`), JUnit and SARIF (review findings), plus a GitHub Actions workflow template (`weave gate --print-workflow github`) and a Jenkins snippet (the repo uses Jenkins).
- **R35** The gate is read-only with respect to production data and the repository.

## 7. Fabric integration (R36 to R40)

- **R36** On an incident row and on a run page, Fabric shows **Create test** and **Try a change**. They are served by endpoints that run the same code (`/api/runs/{id}/capture`, `/api/runs/{id}/whatif`) on the Fabric host, which holds the journals only when the runs' store is shared with it.
- **R37** When Fabric cannot read the journal (a remote client), the action shows the exact `weave capture …` command to run where the store lives, rather than failing.
- **R38** Capture from Fabric returns the case as a file download or writes it into a configured folder; **it never writes into a source repository itself**. The developer commits it.
- **R39** Access control follows Fabric's existing gate: capture exposes run content, so it requires the same permission as viewing run detail plus an explicit `capture` permission; every capture is recorded in Fabric's audit trail (who, which run, when).
- **R40** The incident → test link is kept: a captured case carries `incident:<kind>` and the incident page shows "N tests created from this kind of incident", the visible closing of the loop.

## 8. The loop in one picture

```
Fabric incident ──► weave capture ──► case (observed) ──► human approves ──► tests/ (repo)
                                                                                     │
candidate change (prompt/script/model) ──► weave regress (real history + approved cases) ──► weave gate (CI)
                                                                                     │
                                                                              merge ──► run in production ──► Fabric
```

## 9. Architecture

```
loop/
  Capture, CaseWriter, Masker (wraps PII + SecretScrubber), Consent, Candidates (list/select), Approval
  Regress, Sampler (random|recent|stratified|failures-first, seeded), Population, RunComparator, UnsafeFlip, Report (text/json/md/junit/html)
  Gate, GateConfig, Stages (check|review|test|regress|eval), Workflows (templates)
  StoreReader (read-only run store access, hash-verified)
cli/ CaptureCommand, RegressCommand, GateCommand
fabric/ capture and whatif endpoints (loom-fabric), UI actions (loom-inspect-core ui)
```

- **R41** All run access goes through `StoreReader`, which is read-only by construction (no write methods), takes a lock-free snapshot, and tolerates a store being written by live runs.
- **R42** `loop/` depends on `travel`, `wtest`, `review` and `eval`; none of them depend on `loop/`.

## 10. Sub-phases

| | Delivers | Tests |
|---|---|---|
| 14a | `StoreReader`, `Masker`, consent, `weave capture` (test form), verification of the captured case, approval states, `--list`/`--select` | `CaptureCaseValidTest`, `CapturePassesTest`, `MaskingNoRawOnDiskTest`, `SecretsNeverCapturedTest`, `ApprovalStatesTest`, `CaptureIdempotentTest` |
| 14b | `weave regress` with recorded models: sampling, comparison, unsafe flips, report formats, store hash check | `SamplerTest`, `RunComparatorTest`, `UnsafeFlipTest`, `RegressReadOnlyTest`, `RegressReportGoldenTest` |
| 14c | Live models under a cap, judges, cost estimation, approved-cases-always, speed | `RegressLiveCapTest`, `JudgeCostGateTest`, `ApprovedAlwaysIncludedTest`, `RegressSpeedTest` |
| 14d | `weave gate`, config, git refs, md/junit/sarif, workflow templates; `--as golden` | `GateStagesTest`, `GateSkipVisibleTest`, `GateGitRefsTest`, `GateWorkflowTemplateTest`, `CaptureAsGoldenTest` |
| 14e | Fabric actions and endpoints, permissions, audit; docs | `FabricCaptureRouteTest`, `FabricCapturePermissionTest`, `fabric-capture.page.test.js` |

## 11. Decisions to confirm (open questions)

1. **Where do run stores live in CI?** `regress` needs history. Options: a shared directory or object-store mount the CI can read; an exported, masked "history pack" (`weave history export`) checked in or stored as a CI artifact; Fabric as the source. Recommendation: support a directory first, add `weave history export --masked` (a portable, masked archive of recent runs) in 14d so CI never needs production access. Confirm.
2. **Approval identity.** OS user vs git identity vs `--by`. Recommendation: git `user.email` if available, else OS user; recorded, not authenticated (it is a review aid, not a security control).
3. **Masking and replay.** Format-preserving masking may still change a model's behaviour in a live replay. Recommendation: document it; regress under `recorded` is unaffected because model replies are recorded masked as well. Confirm.
4. **Retention default** (`max-age`). 30 days for captured text in the repository? Recommendation: no automatic deletion (it is in git); the tool warns about age in `weave next`. Confirm.
5. **Observed vs approved default for `weave test`.** Run both (visibility) but only approved gate. Confirm.
