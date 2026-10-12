# Incidents and the rules file

[← Wiki home](Home.md) · [Observability overview](../docs/OBSERVABILITY.md)

The page flags trouble as it happens, in the *Needs attention* strip, an Incidents panel and a badge on the run's tab.
**An incident changes nothing about a run.** It only decides what the console says. How a workflow is watched is kept
**out of the Loom script**, in a file beside it, so the script stays as readable as it was and the same script can be
watched differently in test and in production.

## The incidents

| Incident | Severity | Fires when (defaults) | What it means |
|---|---|---|---|
| **Looping: same call** | warn | an agent makes the same tool call (same tool, same input) **4** times in one step; rounds of a loop count together | the agent is stuck repeating itself |
| **Looping: same answer** | warn | the same step gives the same answer **3** times in a row | a loop is not converging |
| **No progress** | warn | a workflow variable you name is unchanged at the end of **N** loop rounds in a row | the loop's work is not moving the result |
| **Failing tool** | warn, high at twice the threshold (**6**) | a tool fails **3** times in a row | a dependency is down or the agent is calling it wrongly |
| **Suspect output** | warn at **2**, high at **4** | figures in an answer appear in nothing the step was given or fetched | *no source was found*. It does not mean the figures are false |
| **Paused** | info, warn after **60 s** | a pause by a rate limit or budget window | information, not an error; raised to a warning when long |
| **Budget** | warn when nearly spent, high when it ran out | a budget reaches its `warn_at`, then its limit | the budget is nearly or fully used |

An incident opens when its rule fires and closes when its condition clears or the step ends. Suspect-output incidents carry
the signals that fired and are never closed by the tool as "resolved".

![The incidents panel of a run that loops, with the measurement and threshold behind each incident](../docs/images/observability/fabric-incidents.png)

### How they are decided, and why they work at every detail level

The checks use **hashes and counts computed in the process** (a hash of each tool call, a hash of each answer, the count of
figures in an answer that match nothing the step saw), not the text itself. So they work with `--observe-detail metadata`,
when no text leaves the process. `no_progress` reads a workflow variable on the run's own thread at the start of each
loop round and records only a **hash** of it.

## The rules file

`flow.inspect` next to `flow.loom` (found by name), or any file given with `--observe-rules <file>` (which wins). Blocks by
agent name, `*` for every agent; `#` and `//` start comments; one rule per line or several separated by `;`.

```
# flow.inspect
Editor {
    same_action: 4 times                    # the same tool call, rounds of a loop counted together
    same_output: 3 times                    # the same answer, in a row
    tool_failures: 3 times                  # a tool failing, in a row
    no_progress: 3 rounds on review.score   # this workflow variable unchanged at the end of 3 loop rounds in a row
}
Scriptwriter { ungrounded_figures: warn at 2, high at 4 }
*            { paused_longer_than: 90s }    # for every agent that does not say otherwise
```

- A rule left out keeps its default. An agent's block overrides the `*` block rule by rule.
- A script with no rules file is watched with the defaults.
- Nothing in the file changes how an agent runs, so it cannot affect an agent's earned autonomy.
- The rules are read when the process attaches. The embedded page gets them directly; Fabric gets them in the run's
  snapshot, so **the server needs neither the script nor the file**.
- A pause belongs to the run, so the shortest `paused_longer_than` anyone set decides when it becomes a warning.

### Rules kept on the Fabric server

An admin can also keep rules **centrally**, on Fabric's Configuration page, in this same format. They are laid over each run's
own rules **rule by rule**, so one setting changed centrally does not discard the rest of a process's file, and they apply
at once to every run, running ones included. Use them to tune thresholds for a whole fleet without touching any machine.
The one thing a server cannot do is read a workflow variable: for `no_progress`, the process's own rules must name the
variable, and the rounds set centrally replace its count. See [Fabric server](Fabric-Server.md#configuration-page).

### Check a rules file without running anything

```bash
weave observe rules flow.loom                         # uses flow.inspect
weave observe rules flow.loom --observe-rules prod.inspect
```

It refuses an agent the script does not have (and suggests the nearest name), a `no_progress` variable that no step stores
and no workflow takes, a number below 1, an unknown rule (nearest rule suggested), a `warn` level not below its `high`
level, and a `*` block with `no_progress`; a block that sets nothing is a warning. When a run starts, a file with errors is
reported once and **not used** (the defaults apply). The workflow is never affected.

## Not built

- `unsupported_claims … from <field>` (a reviewer's structured count against a share).
- The first round's start is not a loop boundary, so the earliest `no_progress: N rounds` fires is the start of round N+1.

## See also

[Observability](../docs/OBSERVABILITY.md) · [Fabric server](Fabric-Server.md) ·
[Reference](Observability-Reference.md) · [Try it with the simulator](../src/examples/fabric-sim/README.md)
(it is built to trigger every incident above)
