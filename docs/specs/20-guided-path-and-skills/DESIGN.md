# Phase 20 design: the guided path and the skills (a roadmap, a preflight, and a process that is kept)

Status: **draft for review**. Layer: Loom CLI (`weave preflight` new; `weave next`, `weave init`, `weave guide` extended), the four skills (`.claude/skills/*`), the VS Code extension's agent set-up. Part of the [field findings program](../FIELD_FINDINGS.md); read its §2 and decision D7 in §5 first.
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Builds on [Phase 16](../16-first-run/DESIGN.md) (`weave next` as the spine), [Phase 17](../17-honest-results/DESIGN.md) (honest results), [Phase 18](../18-install-and-identity/DESIGN.md) (install, secrets) and [Phase 23](../23-eval-design-skill/DESIGN.md) (the evaluation design skill).
Findings owned: **F28 to F36**.

## 1. Problem

The skills carry a good process: stages, a checklist, five questions per agent for the dataset, a real answer read under a cap before declaring content right, both key routes offered together. In the field session the assistant **did not follow it**, and the person did not know what the whole process was. The assistant never printed the roadmap or used `weave next`, asked three multiple-choice
questions where the skill calls for five open ones and filled in the rest (including scenarios the person had not chosen), built a planner model call without asking whether the planner should be a model, read its first real answer only after the dataset and tests existed, offered only one key route (and the person put keys in `.env.example`), skipped `weave explain` read-back and `weave generate --gate-only`, described a dashboard from the skill's wording before opening it, and never ran the README's commands.

Some of that is the assistant's discipline, and a skill can only ask for it. But **much of it can be made cheaper to do right than wrong**: a command that gives the roadmap so no one has to recite it, a preflight that finds the real problems for a few cents so no one has to remember to look, a record that marks what a person has and has not reviewed. This phase does both: it adds the missing tools, and it sharpens the skills' rules so that the process is **checkable**.

## 2. Goals and non-goals

Goals:

- `weave next` is **the** roadmap and checklist, for the CLI, the skills and the editor, from one source of truth (R2).
- A **preflight** exists as a command and as a stage: the model answers, one real answer is whole, the shared prompt reaches the model, grounded agents return sources, price and window are known, the key resolves (R1).
- The skills' rules that the field session broke are explicit, short, and enforceable by what the tools record (R3).
- A person can tell, from the dataset itself, which cases a person reviewed and which an assistant drafted (R3.1).
- The first thing a skill says about keys is a question, and about the editor, a warning (R3.6, R3.7).

Non-goals:

- Not an evaluation. The preflight proves the plumbing, not the quality (the evaluation design is [Phase 23](../23-eval-design-skill/DESIGN.md), the evaluation itself [Phase 19](../19-evaluation-that-works/DESIGN.md)).
- Not a way to force a coding agent to follow instructions. The tools make the right path cheaper and make skipped steps visible; they cannot compel.
- Not new language features.

## 3. Requirements

### 3.1 `weave preflight` (F30, F31; decision D7)

**R1.1 What it checks.** For a script (and the agents it names), with the keys the project resolves from the secret store or environment, and a **spend cap** (default 0.05 in the script's currency unit, overridable; it asks before spending unless `--yes`):

| # | Check | How | Pass means |
|---|---|---|---|
| C1 | **The key resolves** for each provider host | secret store lookup, no call | a key is bound to the host |
| C2 | **The model is available to this key** | the provider's free "list models" call where one exists, else the cheapest possible request | the model name is accepted |
| C3 | **One answer is whole** | one real call per agent, with its **real prompts** and a probe input (the first dataset case if there is one, else `--input`), reading the finish reason and the output limit ([Phase 17 R2](../17-honest-results/DESIGN.md)) | the answer ended by itself, not at a limit, and the effective limit is stated |
| C4 | **The shared prompt reaches the model** | the same call with a temporary instruction added in memory ("begin your answer with `CANARY-<random>`") placed in the shared prompt, never written to a file | the answer begins with the canary. If it does not, the result says whether the request carried it (the fake-provider checks of Phase 17 settle that offline) or the model ignored it |
| C5 | **A grounded agent returns sources** | the same call for an agent with `grounding:`, reading `{x_citations}` and the search count | at least one source, or "grounded but no sources: searches 0" |
| C6 | **Price and window are known** | the catalogue ([Phase 18 R7](../18-install-and-identity/DESIGN.md)) | both present and not older than 90 days |
| C7 | **The budget is real** | static: the effective output limit and the worst-case cost against `--max-cost` | the worst case is under the cap, or the result says the cap will bite first |

**R1.2 Output and records.** A table of `pass | warn | fail` with **one fix per line**, `--format json`, and exit codes (0 all pass, 1 any fail, 3 stopped by its own cap). It writes `preflight.json` (date, build id, per check result, the cost it spent) into the project's `.loom/` folder, which `weave next` reads (R2). `--mock` runs the same checks against a scripted provider with no spend, so the command can be tested and used in CI.
**R1.3 What it must not do.** It sends **real prompts** to a real model with a probe input, so it asks first, lists what will be sent (agent names, the input's source, the cap) and never sends the content of files outside the script's own prompts and the probe. It does not store answers beyond the first 200 characters of each, marked as such. The canary is random per run and never written to a file. A check it cannot perform (no key) is `skipped: <reason>`, never `pass`.
**R1.4 It is cheap.** The whole preflight for a six-agent script costs at most the cap; each call asks for no more than a small output unless C3 needs the full answer, in which case it uses the agent's own limit. The report prints what it cost.

### 3.2 `weave next` is the roadmap (F28)

**R2.1 The stages.** `weave next` computes the state of each stage of the journey from the project's files and the last recorded results, not from asking: **1 decide agents** (the script has agents; `HARNESS.md` if the project made one) → **2 design the evaluation** (`EVAL_DESIGN.md`) → **3 golden dataset** (`eval/golden`, reviewed share) → **4 script** (`weave check` clean) → **5 audit and review** (no unaccepted HIGH) → **6 scripted tests** (`weave test`, coverage) → **7 preflight** (`preflight.json`, fresh against the script's hash) → **8 one capped real run, read by a person** (recorded when the person confirms) → **9 real evaluation** (an eval bundle for this dataset version and configuration) → **10 deploy and watch** (deployment files, Fabric reporting).
Each stage is `done`, `failing` (with the reason), `not started`, or `skipped (reason)`. A stage may be skipped on purpose, with a recorded reason (the guide's "optional" stages), and the roadmap shows that. A result goes **stale** when its input changed (a changed script invalidates the preflight and the real run; a changed dataset version invalidates the evaluation).
**R2.2 Machine-readable.** `weave next --format json` returns the stages with their states, the one primary next step and its command, for the skills and the editor ([Phase 21](../21-editor-polish/DESIGN.md) R3, decision D10). The text form is the one-screen roadmap.
**R2.3 One source.** The closing hint of every command and `weave next` agree ([Phase 16](../16-first-run/DESIGN.md) A11 still holds); the skills do not carry their own list of stages that can drift from it: they read `weave next`, and carry a static copy only for when `weave` is unavailable, guarded by a drift test.

### 3.3 The skills' rules, made checkable (F29, F31, F32, F33, F34, F35, F36)

Each rule goes into the skills' `SKILL.md` as a short imperative, and where the tools can record that it happened, they do.

**R3.1 Datasets are co-written, and the dataset shows who wrote what (F29).** The skill asks the five questions per agent of chapter 11 of the guide, one agent at a time, and writes cases only from the answers. If the person asks it to draft ("just give me some"), it may, but each drafted case carries `author: assistant` and `reviewed: false`; the person accepts or edits cases one by one, which sets `reviewed_by:`. The dataset schema gains optional `author`, `reviewed_by` and `source`; `weave eval --check` reports the **share of cases not reviewed by a person**, and the report's "Read this first" strip shows it ([Phase 19 §5](../19-evaluation-that-works/DESIGN.md)). A skill never invents a scenario the person did not choose; it **asks** for unanswerable, obvious-answer-wrong and conservative-action cases.
**R3.2 The planner question (F35).** At stage 1, for every coordination step in the person's description (a planner, a router, a supervisor, a merger), the skill asks **"is this a model call, or can the script decide?"** before it designs anything, and records the answer in the design.
**R3.3 Read a real answer early (F31).** After the preflight, the skill runs **one capped real run** and shows the person the output; it does not build the dataset until the person says what is right and wrong with it. `weave next` records "read" when the person confirms (`weave next --confirm real-run`).
**R3.4 Read back, and describe only what has been read (F34).** After writing or changing a script the skill runs `weave explain` and reads the result back to the person for confirmation **before** tests are written; for a generated project it runs `weave generate --gate-only`. It never describes a report, a dashboard or a graph it has not opened and read in this session; if it cannot open it, it says so.
**R3.5 The README's commands are run once (F36).** Before presenting a project as ready, the skill runs each command in the project's README in a **non-spending form** (`--check`, `--mock`, `--dry-run`, or the real command with the cap) and records which it ran. A command that cannot be run is listed as **untested**, never silently left in. The project's generated README marks each command `tested` or `untested`.
**R3.6 Keys: ask first, offer both (F32).** The first question about keys is **"will you run this yourself, or deploy it?"**, then both routes are offered side by side (the secret store, and a `.env` that is git-ignored), with the exact commands, and the sentence **"never in `.env.example`"**; the skill never suggests typing a key into a committed file, and never asks for a key in the conversation.
**R3.7 Selection shares (F33).** The skill says, once, in plain words: **selecting text in the editor sends it to the conversation**; it names the files not to select (`.env`, `*.key`, the secret store's master key, the Fabric admin password file), and offers to add **deny rules** for them to the project's coding-agent settings (for Claude Code, `permissions.deny` entries in `.claude/settings.json`), showing the lines first and writing them only on a yes. `weave init` prints the same snippet, and the extension's **Set Up My Coding Agent** offers it ([Phase 18 R5](../18-install-and-identity/DESIGN.md)).
**R3.8 The roadmap is shown first, the checklist after every stage (F28).** Every skill begins with the roadmap (from `weave next`, or the static copy), and ends every stage with the checklist, in **one format shared by all four skills** (a `references/conventions.md` that is identical in each, guarded by a drift test).
**R3.9 Skipping is visible.** A step the skill skips (the person declines the preflight; the evaluation is skipped) is recorded with the person's reason, in the design record and in `weave next`, not dropped.

### 3.4 The handover to the other skills

The workflow skill's stages are renumbered to the roadmap above; stage 2 hands to `llm4j-eval-design`; the preflight stage calls `weave preflight`; the harness skill points at the same roadmap; the deployer skill's first step is `weave next`'s stage 10. Each skill's description lists the phrases that should bring it in, and the four descriptions do not overlap in a way that sends a request to the wrong one (a test, §4 of the TEST_STRATEGY).

## 4. Compatibility

| Change | Existing projects and skills |
|---|---|
| `weave preflight` | New command; nothing runs it unless asked. |
| `weave next` stages | The stages are a superset of today's; the primary next step for an existing project stays the same unless a new stage (evaluation design, preflight) is first undone, in which case `next` says it, **once**, as a suggestion, not a block. |
| Dataset fields | `author`, `reviewed_by`, `source` are optional; datasets without them are read as `reviewed` and `source: unknown`, with one line from `--check`. |
| Skills | Wording changes; installed copies are replaced by `weave guide --install-skill` (which keeps what the person wrote in agent instruction files, as today). |

## 5. Security and privacy

- The preflight is the first command that sends prompts to a model **by design**: it asks, lists, caps, and stores no answer text beyond a short excerpt. Probe inputs come from the person's own dataset or flag.
- Deny rules (R3.7) reduce accidental disclosure; they are suggested, shown, and written only with consent, and the text says they do not make anything secret that is already shared.
- `.loom/preflight.json` holds no keys, prompts or answers beyond the excerpt; it is meant to be git-ignored and `weave init` adds it.

## 6. Open questions

1. R1.1: should C3 and C4 share one call (the canary rides on the whole-answer call) to halve the cost? Proposed: yes, one call per agent.
2. R2.1: should `weave next` read the VS Code extension's last review, or only files? Proposed: only files and recorded results, so it works anywhere.
3. R3.1: is `reviewed: false` on drafted cases enough, or must drafted cases be written to a separate file until accepted? Proposed: the flag, plus the warning; a separate file makes the dataset harder to use.
4. R3.7: which coding agents have a deny mechanism the skill can write for? Proposed: Claude Code (`.claude/settings.json`) now; the others get a plain list of files to avoid selecting.

## 7. Documentation affected

The four skills; `docs/guide/` (README and chapters 6, 7, 9, 11), the generated pages for `weave next`, `preflight`, `init`, `guide`; the Loom guide; the extension README (set-up and the Outline's source of truth); `docs/specs/16-first-run` (the spine now has more stages); `llms.txt`; release notes; the developer guide's journey.
