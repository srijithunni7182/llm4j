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
- **Pin the expected path** of the workflow and the exact number of calls; a refactor that changes either should be a conscious decision.
- **Treat failures as findings.** A failing check is information; read the case, fix the prompt or tool, re-run from cache.

## Don't

- Don't read results from fake models: they check the wiring only.
- Don't name workflow variables like ordinary words (Loom replaces them inside strings).
- Don't rely on a budget to produce a partial result: it stops the run.
- Don't optimize prompts before they have tests, or apply a patch that did not `generalize()`.
- Don't put keys in files, scripts or chat. If you do, rotate them.
- Don't let one stage's tight ceiling cancel the rest: order stages so cheap, informative ones run first, and make a tripped ceiling an explicit stop, not a silent cascade of errors.

## Readiness checklist

- [ ] Each agent: one job, listed tools, tested prompt, temperature with a reason
- [ ] Golden dataset passes its own test; every dimension covered; injection and fabricated cases present
- [ ] Prompt tests pass; candidates do not regress; optimizer patches only if `generalized()`
- [ ] Agent tests meet their goals; judge noise measured; costs measured and modelled
- [ ] Workflow script loads (`weave check`); audit clean or every finding explained (`weave audit --fail-on medium`)
- [ ] Trajectory tests (path, branches, rounds, call count, budget stop) pass for free in CI
- [ ] Provider limits set; caps below them; stage ceilings from measured costs
- [ ] Keys in the environment only; `audit` logging on; journal on for real runs
- [ ] Smoke run done; first real run within twice the model; report reviewed

Start again at [the map](README.md) whenever a stage fails its gate: the order is the point.
