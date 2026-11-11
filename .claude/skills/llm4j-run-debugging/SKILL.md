---
name: llm4j-run-debugging
description: Finds out why an llm4j or Loom run went wrong and fixes it with a test that keeps it fixed. Use when a workflow gave a wrong answer, took the wrong branch, looped, got stuck or paused, failed a step, cost too much, ran slowly, or the user asks "why did it do that", "what would have happened if", "it worked yesterday" or "it keeps looping". Works from the run's journal with no keys and no spend (weave why, whatif, step, timeline, rewind, capture). Building the workflow is llm4j-workflow-guide; watching live runs is llm4j-loom-deployer.
---

# llm4j run debugging

A workflow is a list of choices (`alt`, loops, classifiers, retries) around calls to models. When a run went wrong, the question is always:
**which choice, over which values, and what would have had to be different?** A run that left a journal can answer that for free, without
calling a model, a tool or a person. Do that before you touch a prompt.

**The repository may not be here: never send the user to a repository path and never ask them to fetch one.** What you need is in
`references/`: `triage.md` (symptom to cause to first command) and `commands.md` (what each command does and the options that matter), and in the
tools: `weave man <command>` and `weave <command> --help` are the truth about options. Follow `references/conventions.md`; the parts that
matter here are: read back what you wrote (5), keys: ask first (7), and skipping is visible (9).

## The loop

1. **Get the run.** Ask for the run folder (the `--journal` of `weave run`) or the run id and its store. No journal: say so plainly. Without
   one, you can only re-run; offer a capped re-run with `--journal runs/r1 --trace` and ask first (it spends). Never guess what a run did.
2. **Say what you see before you theorise.** `weave timeline <run>` (each step with attempt, state and cost) then `weave why <run>` (each
   choice, over which values, where each value came from, the way it did not go). Read the output to the person in plain words.
3. **Name the kind of problem.** One of: a *wrong value* (a model's answer or a tool's result was wrong), a *wrong choice* (the script took
   a branch the person did not mean), a *failed step* (no `on_failure`, or the wrong one), a *runaway* (loop, retry or fan-out), a *cost or
   size problem* (prompts that keep growing), or a *wait* (a question nobody answered, a timeout). `references/triage.md` maps symptoms to these.
4. **Try the change before you make it.** `weave whatif <run> --at <step|line|node> ...` shows how the run would have gone with one thing
   different: an answer, a value, a failure, a prompt version, a model, an edited script. It runs nothing and writes nothing. A model's
   answer is never guessed: where the change reaches a prompt, it says which answer it needs. Say that to the person.
5. **Step through when it is still unclear.** `weave step <script> -i name=value --break <line>` runs one statement at a time with mock models
   and no effects; `back` goes a statement back. It needs no key.
6. **Fix the cause, in the smallest place**, and tell the person which kind it was (value, choice, step, runaway, size, wait). Prefer the
   script over the prompt when the problem is a choice: no wording should decide which branch runs.
7. **Make it stay fixed: test first.** `weave capture <run> --as test` turns the run into a test case with its recorded answers scripted in,
   personal data and secrets masked, **observed** until a person reads it and approves it (`weave capture --approve <file>`). Then
   `weave test <script>` must fail before the fix and pass after it. For a model or prompt change, `weave regress <script>` tries it on past
   runs with recorded answers only.
8. **Say what you did not check.** Real answers from a real model are not reproduced by `whatif`, `step` or `capture`; the journal holds no
   times, token counts or costs for individual attempts. Name what remains unverified.

## Rules

- **Read-only first.** `why`, `whatif`, `step`, `timeline` and `regress` change nothing. `rewind`, `reset` and `resume` change the run:
  use `weave fork <run> --to <new-dir>` to work on a copy, and ask the person before changing the original.
- **Never repeat an effect.** A send, a payment or a publish that already happened stays done. `rewind` and `reset` refuse by default when
  effects were done (`--effects ask-first`); do not pass `keep` or `repeat` without the person's yes, and say what each means.
- **A changed script cannot explain an old run.** `weave why` refuses when the script has changed since; give the version the run used
  with `--script`.
- **A model's confidence about itself is not a measurement.** Do not "fix" a wrong branch by lowering a threshold on a number a model wrote.
- **Do not weaken a check to get green.** A failing test is a finding. Read the case, fix the prompt, tool or script.
- **Spend only under a cap and only after asking.** `--models live` in `whatif` or `step` needs `--max-cost` or `--max-tokens`, and `--yes`.
- **A journal holds what the run saw.** Tool results and answers may hold personal data. `weave capture` masks personal data and secrets
  first; for anything else you paste into an issue, read it first and mask what should not leave.

## When it is not a debugging problem

- The workflow was never good: the golden dataset and `weave eval` (`llm4j-workflow-guide`, `llm4j-eval-design`).
- The design lets it loop or stop badly: bounds, durability and who may step in (`llm4j-harness-design`).
- It is running now, and people must watch it: the live page and incidents (`llm4j-loom-deployer`).
