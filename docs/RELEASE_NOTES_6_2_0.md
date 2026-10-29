# Release notes: 6.2.0

Status: **in progress** (the field findings program, [docs/specs/FIELD_FINDINGS.md](specs/FIELD_FINDINGS.md)). This page lists what is finished so far; each item says what changed for you.

## Fixed: answers are no longer cut short without a word

- **No more silent 1,024-token limit.** With a token or cost budget and no `per_call`, every model call was capped at 1,024 output tokens (`BudgetSet.DEFAULT_OUTPUT`), so long answers ended mid-sentence while the run
  reported success. The limit is now the model's known maximum (from the seeded model list; 8,192 for a model not on it), bounded by what the budget still affords. **Behaviour change:** answers that were clipped
  become whole, so token use rises to what the agent really writes, still inside the budget. A script that relied on the clipping to save cost should set `per_call`. Only up to 8,192 tokens per call are held back
  from the budget while the call runs (`BudgetSet.UNSPECIFIED_HOLD`), so parallel branches are not starved.
- **A cut answer is named.** When a provider stops an answer at its limit, the run prints `✂ N answers were cut at the output limit: Writer (2)`. The count was already in the trace as `truncated`.
- **`weave explain` states each agent's output limit** and where it comes from (its `per_call`, the model's maximum, or the default).
- **A cost that is an estimate says so.** The spend table marks estimated rows `~$` and names the agents whose provider reported no usage, instead of the generic note.
- **Gemini grounding with `recency:` works again on clocks with sub-second precision.** The times sent are whole seconds. (Verified by a unit test; a recorded request from the real API is still owed.)

## Fixed: what a script declares now reaches the run

- **`shared_prompt` is applied.** In 6.1.0 and earlier no `weave` command (`run`, `eval`, `test`, `serve`) told the agents the shared prompt, in either form (`shared_prompt: "text"` or
  `shared_prompt: prompt "house-rules"`). It is now put first in every agent's system text. If your workflow relied on rules in the shared prompt, they were not in force before; check a run's behaviour
  after upgrading. See the correction in [RELEASE_NOTES_6_1_0.md](RELEASE_NOTES_6_1_0.md).
- **`weave check` no longer calls a named shared prompt file "not used by any agent".** It was the same fault.
- **`supervise` and `intervention` blocks are applied.** A script's own `supervise Agent { ... }` rules and `intervention { ... }` defaults were dropped when the script was loaded, so a supervised workflow ran
  unsupervised and `weave explain` did not list them. They now apply, including `supervise` blocks in imported files. An imported file's `shared_prompt` is not imposed on the file that imports it.
