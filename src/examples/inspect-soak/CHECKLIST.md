# Soak checklist: what loom-inspect must show

Times are estimates (the first live run will correct them), counted from the start. Each `sleep` is 90 seconds, the last 4 minutes.
"Needs" is the build phase of the design (section 12) that makes the row pass.

## Timeline

| About | Happens | The console must show | Needs |
|---|---|---|---|
| 0:00 | Run starts; Showrunner writes the brief | RUNNING, one agent active, tokens and cost rising from zero; the graph shows step 1 running | 0b |
| 0:01 | `broadcast`: Researcher, TrendScout, Competitor at once | three agents running together; Wiki and Example tool calls in flight; the Following panel streams plan, tool, observation lines | 1 |
| 0:02 | Competitor's fetches of `/pricing` and `/blog` fail | the tool-activity row for Example shows errors and retries; the agent row shows its failed calls | 1 |
| 0:03 | Research finishes; `sleep 90s` | the run shown as **Sleeping** with a countdown to the wake time (not Paused, not failed); no agent running; the sleep step drawn as a clock node | 1 |
| 0:05 | Loop round 1: Scriptwriter, then Editor, FactChecker and SEO in parallel | loop box with "round 1 / 5"; three parallel nodes running; Editor's Wiki call "video hook patterns" | 1 |
| 0:08 | Round 2. Same score 5, same fix, same Wiki call | the repeated action count for Editor rises | 3 |
| 0:11 | Round 3 | **Looping** incident: same fix three rounds, flat score, identical call ×3; attention strip shows it | 3 |
| 0:11 | Scriptwriter's statistics have no sources | **Suspect output** incident with the ungrounded figures listed; FactChecker's unsupported count above zero | 3 |
| 0:14 to 0:16 | Rounds 4 and 5; the loop gives up | "round 5 / 5", then the loop's `on_exhausted` note; the Looping incident closes when the loop ends | 3 |
| 0:17 | `checkpoint polish`; Planner; `parallel for each` over three shots | three Designer and three Captioner branches; the for-each fan-out visible | 1 |
| 0:18 | Captioner exceeds 2,500 tokens a minute | **Paused**, not failed: hatched node, countdown to the next minute, other branches finish first | 3 |
| 0:19 | The window refills; the run resumes | the pause incident closes; "resumed" shown; no step repeats (the journal replays the finished ones) | 3 |
| 0:21 | Auditor scores 3 | a **rewind** event: back to `polish`, "1 of 1 used", the old attempt kept as history | 1 |
| 0:22 to 0:26 | Planner, for each and Auditor again; score 8 | the second pass runs; spend keeps rising; cost projection updates | 1 |
| 0:27 | Publisher saves `report.md` (or, with `ASK_HUMAN=yes`, waits) | with the gate: **Waiting on you**, one approval pending, "Needs you" count 1 | 1 |
| 0:28 to 0:31 | Final `sleep 4m`, then done | the run ends FINISHED; totals match `spend()` | 0b |

## Pass criteria

1. **Accuracy.** Final tokens and cost on the page equal the `spend` report within 1% (within the estimate
   flag for any call the provider reported no usage for).
2. **No lost events.** The `loom.seq` of each run is continuous, including across a server outage; the page and the server agree on the event count, and the process's outbox is empty once the server has caught up.
3. **Latency.** A new event appears on the page within 2 seconds in embedded mode and within 3 seconds
   through the server (a batch is one second).
4. **Truthful states.** Nothing is shown as running when it is not. During a `sleep` or the budget-window pause,
   no agent is shown as active.
5. **The run is untouched.** Wall-clock time and tokens with and without loom-inspect attached differ by
   no more than noise.

## Chaos steps (do these during the run, with the server)

| At about | Do | Must happen |
|---|---|---|
| 0:07 | Stop the inspect server for 2 minutes | the workflow is unaffected (same timing, same tokens); the outbox on the process grows; the console shows **Stale**; on return a snapshot arrives, then the backlog streams in (**Catching up**, backlog falling) and the chip returns to **Live**; the outage window is filled in with its original timestamps; no events lost |
| 0:12 | Restart the server | live state is rebuilt from disk; the run reappears; the process resends from its cursor and the server drops duplicates |
| 0:15 | Block the network between process and server for 30 seconds | **Lagging**, then catches up |
| 0:20 | Kill the weave process (kill -9), then start it again with the same journal and name | the run resumes; the new process drains the dead instance's outbox first, so at most the last second before the kill is missing and the gap is shown; finished steps are not repeated; a sleep keeps its original wake time |
| 0:23 | Start a second copy with another `--observe-name` | two process groups of tabs, no mixing of their runs |
| 0:25 | (after Phase 5) press Pause, then Resume, then Stop | each shows "queued" until the process confirms, then the new state |
| 0:26 | Stop the server and **leave it down** until the run finishes, then start it | the run finishes normally with the server down; on start the whole backlog arrives and the run appears complete; the process's outbox is then empty |
