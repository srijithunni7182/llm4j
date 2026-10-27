# Working with Loom (for a coding agent)

This project holds Loom workflows: `.loom` scripts that `weave` checks, tests, runs and ships. You are asked to build and change them for a person. This page is short on purpose; read the rest from the tool, not from memory.

## Look things up, do not guess

- `weave man` lists every command by what a person is doing; `weave man <command>` prints one page (every option with its default, examples, exit codes). Use it before you write a flag you have not seen in this session.
- `weave guide` lists the chapters; `weave guide loom` prints the language reference; `weave guide 6` the chapter on building a workflow.
- `weave next` says what to do next in this project, in order. It is free.
- If `.claude/skills/llm4j-*` exists, those skills hold the long version of everything below.

## The loop

1. **Describe it, or change it.** New: `weave generate "what it should do" --plan-only`, read `PLAN.md` to the person and ask if it is what they meant, then `weave generate --from-plan plan.json`. Change: `weave generate --edit main.loom "the change"` shows a diff and writes nothing until `--apply`. Writing a script by hand is fine; then run `weave generate --gate-only .` before you present it.
2. **Check and review.** `weave check main.loom --no-env`, then `weave review main.loom`. A high finding (the lethal trifecta, an effect with no approval, no budget) is yours to fix before you show the work.
3. **Show it.** `weave graph main.loom --format mermaid` and `weave explain main.loom` (plain English). Read the explanation back and ask whether it is what they meant.
4. **Test it.** `weave test main.loom --coverage`. Tests use scripted models: free, instant, the same every time. `tests/derived.test.yaml` is written by `weave generate` and records what the script does; do not edit it by hand, change the script. Read `tests/LEFT_OUT.md` if it exists: those are cases from the plan that the script does not satisfy.
5. **Evaluate.** `weave eval main.loom --mock` is free. Against real models it costs money: give `--max-cost` and `--prices`, and ask first.
6. **Watch a real run.** `weave run main.loom --observe` serves a live page. `weave why <run>` explains the choices a run made.
7. **Close the loop from production.** `weave capture <run-id>` turns a real run into a masked test case (a person must read and approve it); `weave regress main.loom` tries a change on past runs; `weave gate` is the one verdict for CI.
8. **Ship it.** `weave bindgen` writes typed Java for the workflow; `weave deploy` writes Docker, compose or Kubernetes files from the script.

## Rules

- **Never write a key, token or password into any file.** The script reads `env.NAME`; keys live in `.env` (ignored by git) or the secret store. `weave check --no-env` works without keys.
- **Ask before anything that spends money or has an effect** (a real run, `weave eval` without `--mock`, a send or a write). Set `--max-cost` with `--prices` when you do run one.
- **Free commands:** check, review, audit, graph, explain, test, eval --mock, next, man, guide, doctor, try, deploy, bindgen. **Cost model tokens:** `weave generate` (a few cents with a cheap model), `weave eval` and `weave run` against real models.
- **Do not skip or weaken a test to make a gate pass.** If a case fails, either the script or the case is wrong; say which.
- **Text only in, text only out:** workflow inputs are text. A script that reads fields of an input (`record.count > 100`) will not work from the command line.
- When a command fails, read its message and `weave man <command>`; do not retry with different flags until you know what they mean.
