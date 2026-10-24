# Reviewing a workflow: `weave review`

`weave review script.loom` reads a Loom workflow and tells you, in one ranked list, what is worth a second look: what it can reach (security), whether it is valid, what it costs and how long it takes, how it fails, how its prompts are written, whether it can be resumed, whether it is tested, and what it does with personal data. Each finding says what it found, why it matters and what to do about it.

Review **advises and never blocks**. It runs nothing (no model, tool, task or network), needs no API keys, and exits 0 unless you ask it to fail (`--fail-on`). `weave run` and `weave package` print one line to standard error when findings are open and then carry on.

The reference for every rule is in [`rules/`](rules/index.md). To add your own rule, see [WRITING_RULES.md](WRITING_RULES.md).

## The first five minutes

```
weave review main.loom                 # the ranked list, with a summary card
weave review main.loom --explain LR01  # what one rule checks, why, and what to do
weave review main.loom --fix --dry-run # what the safe fixes would change (a diff)
weave review main.loom --assist        # go through the findings that have options, with you deciding
weave review main.loom --lens all      # also the optional lenses
```

## What it looks at: lenses

| Lens | Question | On by default |
|---|---|---|
| Security | What can this reach, and who can make it act? (the audit, `LA01` to `LA20`) | yes |
| Validity | Does it load and make sense? (`weave check`, `LV`) | yes |
| Cost and performance | What does it cost, how long does it take? (`LC`) | yes |
| Reliability | What happens when something fails? (`LR`) | yes |
| Prompts | Are the prompts clear, safe, bounded? (`LP`) | yes |
| Observability | Could you see what it did? (`LO`) | yes |
| Durability | Can it stop and resume without doing things twice? (`LD`) | yes |
| Privacy and compliance | What does it do with personal data? (`LY`) | yes |
| Testability | Is it tested? (`LT`) | no, until `weave test` exists |
| Model governance, Maintainability, Human oversight, Blast radius | `LG`, `LM`, `LH`, `LB` | no |

`--lens all` turns everything on; `--lens +oversight` adds one; `--lens security,cost` runs exactly those; `lenses = +governance,-prompts` in `review.properties` makes it a project choice. The summary card says how many lenses are off.

## Reading a finding

```
MEDIUM  LR01  Reliability     main.loom:12
        delegate Researcher has a tool but no retry or timeout
        why: A tool call can fail or hang. Without a retry and a timeout one slow or failing call stalls or ends the whole run.
        fix: Add a retry and a timeout to this step.
        options: [retry-timeout] Retry and time out [timeout-only] Time out only, no retry
```

Every finding has a **fingerprint** (a short stable id from the rule, the thing it is about and its wording, not the line number), which is how you refer to it when you fix or accept it. `--format json` shows it, with the same text, the location, the OWASP LLM Top 10 id when there is one, and the fix and options. `--format sarif` is for code scanning; `--format md` is for a pull request.

## Handling findings without editing by hand

You decide what happens to a finding. There are four ways to handle one.

1. **Fix it yourself**, then run review again.
2. **Let review make the edit.**
   - `--fix` applies the fixes that have one obviously right answer and no effect on behaviour. Each result is checked: the script still loads and no new high finding appears. `--dry-run` prints the diff and changes nothing.
   - `--assist` goes through the findings that have **options** (several reasonable resolutions) one at a time: you choose, answer the questions the option asks (how many retries, what a failure note says), see the diff and confirm.
   - From a script or an agent: `--fix-finding FINGERPRINT --option ID --set name=value [--dry-run] [--yes]`. An option marked as needing thought needs `--yes`.
3. **Accept it** with a reason: `--accept FINGERPRINT --reason "why"` (also `--scope`, `--until`, `--by`). The reason is yours; review never writes one for you. It is saved in `review.properties`, so it is reviewed with the code.
4. **Baseline** an existing script: `--baseline-write` records today's findings as known so only new ones count; `--noise-report` says which rules are accepted almost everywhere.

A fix proves that the script loads and adds no high finding. It does not run the workflow, so after a fix run `weave check` and your tests.

## Cost, time and effects

The summary card gives, for every workflow, what it costs (typical and worst case), how long it takes (typical and worst case, with waits for people and timers listed apart) and how many sends and writes one run can cause. All of it comes from the script alone, from stated assumptions; `weave review --explain cost` lists them and `--assume loop.iterations=5` changes one. A loop with no bound is reported as unbounded, never as a number. `--prices FILE` turns tokens into money; `--calibrate RUN_DIR` compares the estimate with a real run.

## Settings: `review.properties`

Beside the script. A misspelt key is an error that names the nearest valid one.

| Key | Meaning |
|---|---|
| `fail-on = high` | Exit 1 at this severity (default none) |
| `lenses = +governance,-prompts` | Which lenses run |
| `heuristics = on` | Run the rules that guess from names or wording |
| `LR01 = off`, `LR01.severity = low` | Turn a rule off, or change its severity |
| `LR01.accept = finding 3fa9c2d1e0b47a55 "reason" until 2026-12-31` | An accepted finding (written by `--accept`) |
| `notice = off` | Silence the line `run` and `package` print |
| `packs = core@1, acme@2.0.1 sha256:…` | The rule packs in force, pinned |
| `assume.*` | Cost and time assumptions |
| `data.*`, `cost.*`, `blast.*`, `oversight.*`, `workflow.NAME.interactive` | Facts and limits your rules read (for example `data.regions`, `data.personal`, `cost.latency.limit`, `blast.max-effects`) |
| `fix.retry`, `fix.loop.max` | The text the retry and loop fixes insert |

A rule that needs a setting you have not given is reported as **not evaluated**, never as passing.

## When you use a coding agent or a skill

The agent should run `weave review` after writing or changing a workflow, tell you the open counts and the top findings in a sentence, offer to fix or accept them, and carry on with what you asked (run, test, package, deploy). It may recommend fixing first, and says plainly when a finding is high, but the decision is yours. It uses `--fail-on` only when you ask for a gate.
