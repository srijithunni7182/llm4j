# Generating a workflow

`weave generate` turns a description into a project, but it will not call the result done until it has been held to the same checks you would run yourself. It does not run the agents it writes and it sends nothing. The model that writes is the one in your environment (`--model`, or `LOOM_GENERATE_MODEL`), with your key; the key is never written into a file.

## The flow

```bash
weave generate "Triage support emails: answer order-status questions, and send refunds over 100 dollars to a person" --plan-only --out plan
# read plan/PLAN.md; edit plan/plan.json if it is not what you meant
weave generate --from-plan plan/plan.json --out triage
```

1. **Questions** (at most five, only about what changes the design: who approves, what must never be sent without a person, which systems it touches, volume, which models). Each has a proposed answer; Enter takes it. `--no-ask` takes them all and lists them in the plan.
2. **The plan.** What will be built: the agents and their models, the classifiers, the tools **and what each reaches** (`reads`, `fetches`, `writes`, `sends`), who is asked, which effects a person approves (or why none), the budget, what happens on failure, and the tests. A plan with a tool that writes or sends and no approval, or no budget, or an unknown model is refused before anything is written. `--interactive` shows it and asks; `--yes` goes on.
3. **The files**, written into a temporary folder: the script, a prompt file for each agent, the tests, the plan, a golden-dataset stub, a README. **The tool writes most of the tests itself** (`tests/derived.test.yaml`): it runs the script with scripted models and tools down every classifier label, every enum answer, every failing step and every value the script's conditions compare with, and keeps a case for each route it finds. They record what the script does, so they pass by construction and a later change that moves a route fails one. The model writes only the cases your plan lists; one that does not hold is not repaired but listed in `tests/LEFT_OUT.md` (read it: it is either a mistake in the case or a place where the script does not follow the plan).
4. **The gates.** The project is made only when all five pass:

| Gate | Passes when |
|---|---|
| **G1** check | `weave check --no-env` finds no error |
| **G2** review | after the review's safe fixes (applied by the tool, not the model) no high finding is left, and nothing medium in validity, reliability or prompts (`--gate-level`) |
| **G3** tests | every case of the project's own tests passes (scripted models, nothing spent) |
| **G4** coverage | those cases reach at least `--coverage` percent of the steps (default 80) |
| **G5** plan | the script has exactly the agents, classifiers, tools, effects and people the plan lists, effects the plan approves have `approve:`, and there is a budget |

When a gate fails, the model is told what failed and where (the rule, the line, the failing assertion, the steps no test reaches) and gives back only the files that must change. It has at most six tries per gate and twelve in all (a syntax error is reported one at a time); an attempt that is worse than the best so far is thrown away; the generation stops at `--max-cost` (counted when `--prices` knows the model) or `--max-tokens`. If it ends without passing you are told which gate and why (exit 3, or 4 for the spend cap) and **no folder is made**; `--keep-failing` leaves the draft in `<out>.draft`.

G5 is what keeps instructions hidden in a description from adding a tool or an effect: the plan is the only door, and you read it first.

## What it does not check

How good the answers are with a real model (that is `weave eval`), whether the business rules are the ones you meant, and the code inside any tool stub. The card at the end says so.

## Changing a project

```bash
weave generate --edit src/main/resources/main.loom "add a human approval before the reply is sent"
```

The model proposes a **patch** (search-and-replace on the files it names, new files, or one question if the change is ambiguous). It is applied to a trial copy and held to: check, the review **compared with before** (findings added and removed; the worst-case tokens of each workflow, which may not grow more than `--worse-factor`, default 1.5), all the project's own tests (the ones the patch changes are listed), and the plan. You get the diff and those numbers. **Nothing is written** until `--apply`; a change that makes things worse needs `--accept-worse` as well. `--patch-out FILE` keeps the patch, and `--apply-patch FILE` applies it later without asking the model again (only if the files are as they were). A change cannot touch `.env`, keys, anything outside the project, or any file a workflow project does not hold.

## Any project, by the same bar

```bash
weave generate --gate-only my-project/
```

Runs G1 to G5 on a project someone wrote by hand (G3 and G4 are skipped with a note if it has no tests, G5 if it has no `plan.json`). Hand-written projects are never held to it unless you ask.

## In VS Code

**Loom: Generate Workflow...** asks what it should do, opens the plan for you to read and edit, and builds on *Generate*. **Loom: Change Workflow...** (with a selection, if you like) opens the patch as a diff and applies it on *Apply*. The model is called by `weave generate`, a process of its own; the engine that checks and reviews your scripts still runs no model.

## Recording and replaying

`--record FILE` saves the model's side of a generation (a fingerprint of each request, the reply, the tokens; no request text and nothing shaped like a key) and `--replay FILE` answers from it, so a generation can be repeated exactly with no model and shared in a bug report.
