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
| **Context nearly full** | warn at **85 %**, high at **95 %** | an agent's prompt fills that share of its model's usable window (only when the window is known) | the agent is about to run out of room; see the compactions in the evidence. Closes when the context falls below the warn level |
| **Truncated answer** | warn, high at **3** | a model call ended at the token limit (**1** time to raise) | the answer was cut off: raise the limit, shorten the task, or expect a half answer |
| **Compaction thrash** | warn | **4** compactions of one agent within **10** model calls, or **3** in a row that freed little | compaction is not keeping up: the window is too small, the tool results too big, or the strategy too gentle |
| **Tool success rate** | warn below **70 %**, high below **40 %** | a tool's success rate over its last **10** calls (at least 5) | the tool is failing often; the evidence names the most common failure category |
| **Latency spike** | info, warn on a second in a row | a model or tool call lasts more than **5×** the running median of its kind (and over 2 s, after 10 samples) | a provider or dependency is slow right now |
| **Workflow diverted** | warn | a task (`run`) or delegate failed and its `on_failure` block took over | the workflow went on by another road; the evidence names the step, the failure category and the attempts. Closes when the run ends |
| **Workflow broken** | high | the run ended failed because a task or delegate failed with no `on_failure` | the run stopped here; the incident names the step, the task or delegate and the category, and stays open after the run ends so the page still says why |
| **Lethal trifecta** | high; info when accepted; warning when assumed | one agent used a tool of each leg (untrusted content, private data, a way out) in one run | the combination that lets text it reads tell it to send your data out; stays after the run ends. See [Security signals](Observability-Security-Signals.md) |
| **Suspected injection** | warn; high after an unusual next call, or for an agent with an accepted trifecta | a result from an untrusted tool read like instructions (a heuristic: only pattern group names are kept) | look at what the agent did next |
| **Refused access** | warn; info for an unknown tool | a tool refuses access **3** times in **60 s**; a call to a tool the agent does not have | a wrong role or credential, or something steering the agent |
| **Budget** | warn when nearly spent, high when it ran out | a budget reaches its `warn_at`, then its limit | the budget is nearly or fully used |

An incident opens when its rule fires and closes when its condition clears or the step ends. Suspect-output incidents carry
the signals that fired and are never closed by the tool as "resolved".

![The incidents panel of a run that loops, with the measurement and threshold behind each incident](https://raw.githubusercontent.com/srijithunni7182/llm4j/main/docs/images/observability/fabric-incidents.png)

### How they are decided, and why they work at every detail level

The checks use **hashes and counts computed in the process** (a hash of each tool call, a hash of each answer, the count of
figures in an answer that match nothing the step saw), not the text itself. So they work with `--observe-detail metadata`,
when no text leaves the process. `no_progress` reads a workflow variable on the run's own thread at the start of each
loop round and records only a **hash** of it.

## Tools: which ones are failing

Every tool a run uses has a **health**, worked out by one set of rules so that a page can show it and a server can add it up across runs:

| State | When |
|---|---|
| `unused` | no call of it has run yet |
| `failing` | it has run at least 3 times and **never worked**; or its last 3 calls in a row failed; or fewer than 40 % of its last 10 calls succeeded |
| `degraded` | fewer than 70 % of its last 10 calls (at least 5) succeeded; or it has run fewer than 5 times and its last call failed |
| `healthy` | otherwise |

Each state has a one-line reason ("never succeeded in 7 calls", "4 of 6 recent calls succeeded"). A row also carries the recent and overall success rate, the failures by category (`timeout`, `not_found`, `rate_limited`, ...), the last failure and last success, a per-agent breakdown (is one agent misusing the tool, or is the tool broken?) and a latency histogram. A call the runtime never ran (a blocked repeat, an unknown tool, a rejection) is not a failure of the tool. A tool that returns an error as text (`Error: 404 ...`) is counted as failed, with a category read from the text.
A server adds the accounts of every run it knows, with the failing tools first, in `RunRegistry.toolRollup()`. A runnable example is in [`src/examples/inspect-tools`](../src/examples/inspect-tools/README.md).

Tasks get the same account. A run's view lists its tasks beside its tools (calls, success rate, failures by category, latency percentiles, health), and the cross-run roll-up has a `kind` of `task` for them, so a task and a tool with one name stay two rows. A task that fails and is retried counts once, with its attempts in the trace; a task replayed from the journal on a resume counts for nothing in the new process.

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
Researcher {
    context_nearly_full: warn at 85%, high at 95%   # share of the model's usable window
    truncated_answers: 1 times                      # model calls cut off by the token limit
    compaction_thrash: 4 in 10 calls                # compactions within this many model calls
    tool_success_rate: below 70% over 10 calls      # a tool's recent success rate (at least 5 calls)
    latency_spike: 5x p50                           # a call this many times slower than the usual (median)
}
Support {
    access_denied: 3 in 60s                         # a tool refusing access this many times within this span
    injection_scan: on                              # or off: whether results of untrusted tools are scanned for injected instructions
    trifecta: accepted "customer support: reads tickets, looks up the account, replies"   # a written reason is required
}
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
