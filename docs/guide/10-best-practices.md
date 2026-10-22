# 10. Best practices and a checklist

Lessons from building and evaluating Hexamind, in the order they would have saved us time.

## Do

- **Write the dataset before the agents' final prompts.** Rubrics force you to say what "good" is.
- **Include injection and fabricated-premise cases for every agent.** They are cheap and they find real problems.
- **Run free first.** A mock run that exercises the whole pipeline (agents, judge, cache, report) found most of our bugs for $0.
- **Give search-dependent cases recorded results.** An empty search for a real topic sends agents into loops and measures nothing useful.
- **Use a different, stronger judge**, and measure its noise with a calibration pass before trusting small differences.
- **Cache everything that costs money** (judge verdicts, agent runs) so a re-run pays only for what changed.
- **Set caps at three levels** and set stage ceilings from *measured* costs, with margin.
- **Assert on behaviour, not just text:** tools used, arguments, number of delegations, the path taken, the trace free of secrets.
- **Keep roles without tools wherever you can.** They cannot be hijacked into acting.
- **Put money, policy and side effects in tasks, not agents.** Plain Java behind a `run` step is exact, free and unit-testable, and a model can never call it. Let agents read and propose; let code decide and act.
- **Make a task that changes things idempotent**, and hand its `idempotencyKey()` to the provider, so a crash between the payment and the journal never pays twice.
- **Keep a second vendor behind the first.** A `routing` policy with `strategy: fallback` across two vendors (`gpt-5-mini`, then `xai/grok-3`) turns one vendor's outage or quota into a slower answer instead of a failed run; the model name is the only thing that changes.
- **Pin the expected path** of the workflow and the exact number of calls; a refactor that changes either should be a conscious decision.
- **Treat failures as findings.** A failing check is information; read the case, fix the prompt or tool, re-run from cache.

## Don't

- Don't read results from fake models: they check the wiring only.
- Don't name workflow variables like ordinary words (Loom replaces them inside strings).
- Don't rely on a budget to produce a partial result: it stops the run.
- Don't give an agent a tool that moves money and hope its prompt keeps it safe; and don't `retry` a task that changes things unless it is idempotent (`weave check` refuses).
- Don't optimize prompts before they have tests, or apply a patch that did not `generalize()`.
- Don't put keys in files, scripts or chat. If you do, rotate them.
- Don't let an agent that loops on tools run with no plan for its context window: a long tool loop fills it ([chapter 14](14-context-and-compaction.md)).
- Don't accept the lethal trifecta for an agent on your own: the user decides, in writing, with a reason.
- Don't let one stage's tight ceiling cancel the rest: order stages so cheap, informative ones run first, and make a tripped ceiling an explicit stop, not a silent cascade of errors.

## Readiness checklist

- [ ] Each agent: one job, listed tools, tested prompt, temperature with a reason
- [ ] Every step that moves money or must be exact is a task, with an `EffectPolicy` (idempotent or not) and an approval where a person should decide
- [ ] Golden dataset passes its own test; every dimension covered; injection and fabricated cases present
- [ ] Prompt tests pass; candidates do not regress; optimizer patches only if `generalized()`
- [ ] Agent tests meet their goals; judge noise measured; costs measured and modelled
- [ ] Workflow script loads (`weave check`); audit clean or every finding explained (`weave audit --fail-on medium`)
- [ ] Trajectory tests (path, branches, rounds, call count, budget stop) pass for free in CI
- [ ] Provider limits set; caps below them; stage ceilings from measured costs
- [ ] Keys in the environment only; `audit` logging on; journal on for real runs
- [ ] If a person may step in or an agent can loop: steerable agents are marked on purpose and stream, each step whose stop matters has an `on_stopped` or `intervention` default, rewinds name declared checkpoints, and a looping agent has a `supervise` rule or a decision not to ([chapter 15](15-steer-stop-and-supervise.md))
- [ ] Agents that loop on tools have a `context` block, and each model's context window is known ([chapter 14](14-context-and-compaction.md))
- [ ] Every agent holding the lethal trifecta is split or accepted in writing; tools declare their `reach`
- [ ] Every `run` and `delegate` that can fail says what happens (`retry`, `on_failure`, or a decision to stop)
- [ ] A run someone else will watch reports with `--observe` or `--observe-fabric`, set up with the `llm4j-loom-deployer` skill
- [ ] Every step's model was chosen from measured numbers on your own cases, with the date, and the cost per run and per month is estimated ([chapter 16](16-cost-and-model-choice.md))
- [ ] Smoke run done; first real run within twice the model; report reviewed

Start again at [the map](README.md) whenever a stage fails its gate: the order is the point.
