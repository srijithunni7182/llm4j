# How harnesses go wrong

Start from the symptom the user describes. Each row names the usual cause in the harness (not in the model) and the fix, with the
section of `principles.md` that explains it. When reviewing an existing workflow, read down the list and ask whether each one can
happen.

## Runs that do not end, or end badly

| Symptom | Usual cause | Fix | § |
|---|---|---|---|
| Cost climbs within one run; it ends in "prompt too long" | an agent loops on tools with no context plan; every step resends the whole prompt | a compaction plan per looping agent; trim tool results first; know the window | 6 |
| The agent repeats the same tool call | no rule that counts its behaviour; the stop condition depends on its own opinion | a supervise rule on repeated calls (nudge, then wrap up); a step maximum | 4 |
| A loop runs to its maximum every time | the stop condition can never be true, or it reads a variable the body never sets | test the condition on a scripted run; put the stop value in the condition | 4 |
| A loop never runs | the condition is already true before the first round | run the step once before the loop, or test for the stop value, not its absence | 4 |
| The run dies with a pile of errors after a budget is hit | no designed outcome for exhaustion; later stages run on nothing | order stages cheap-first; make exhaustion an explicit stop or a partial result | 4 |
| An effect happened even though the budget was spent | budgets are checked before model calls, not before effects | put must-happen effects early, or make them deterministic steps | 4 |
| A rate limit fails the run at 3 a.m. | limits are treated as errors | suspend and resume on the reset time, with a maximum wait | 4, 14 |
| The run sits silent for hours | waiting on a person nobody can reach, or a stalled step | a reachable channel and a timeout decision; detect stalls as trouble | 8, 14 |

## Runs that do the wrong thing

| Symptom | Usual cause | Fix | § |
|---|---|---|---|
| A payment, email or ticket happened twice | a retry or a resume repeated a non-idempotent effect | idempotency keys; mark the crash window; never retry unknown outcomes | 5, 7 |
| The agent says it is done and it is not | done is declared by the doer | criteria checked by code; a separate checker; fail closed | 2 |
| Scores improve while real quality does not | the agent can see or edit the evaluator, or special-cases the examples | lock the evaluator; held-out cases; behaviour checks; revalidate harness changes | 11 |
| The agent weakened a test or a threshold to pass | the test is an editable surface | make it locked; record and refuse the attempt | 3, 11 |
| A step was skipped | a mandatory step was left to a model | make it a deterministic step the harness always runs | 1, 3 |
| Work after a failure is built on a broken result | no halt; a failure was swallowed | halt with a reason when a stage cannot go on | 7 |
| A retry fails the same way every time | the retry carries no reason forward | carry the error into the next attempt, or take another path | 7 |
| A rewind loops between two bad drafts | no maximum, or nothing new carried forward | a maximum per rewind and per run; carry the reviewer's notes | 4, 5 |
| The planner invents an agent or a step | dynamic work is a free hand | a typed plan from a declared menu; unknown names stop the run | 1 |
| One malformed branch spoils the combined result | branch answers merged without a schema check | check each answer against its schema before merging | 15 |
| A fan-out burns the budget or hits rate limits at once | the list's size is whatever the model produced | cap the width where the plan is checked; mind provider limits | 15 |
| The build refuses the design's retry-with-feedback | the rewind or checkpoint was placed inside a parallel branch or per-item loop | checkpoint before the fan-out, combined check and rewind after it | 4, 15 |

## Runs that are turned against their user

| Symptom | Usual cause | Fix | § |
|---|---|---|---|
| A web page or email made the agent send data out | one agent holds the lethal trifecta | split reading from acting; approval or code check between them | 3 |
| A tool did more than it should (wrote, deleted, called an unlisted host) | the tool's reach is wider than the job | least reach: allowlists, read-only users, no interpreters | 3 |
| A key appeared in a log, a trace or the chat | a secret entered the context or a command line | secrets from a store, bound to their host; never in prompts or logs; rotate | 3 |
| Personal data reached a hosted model | no masking at the agent that sees it | mask or block at that agent | 9 |
| A steered agent was told to do something it should not | steering treated as trusted | mark steerable agents on purpose; an allowlist; guard the channel | 8 |

## Runs nobody can explain afterwards

| Symptom | Usual cause | Fix | § |
|---|---|---|---|
| Nobody knows which step failed or why | failures are not named | the failed step, kind and reason in the journal and audit log | 7 |
| Spend is far above expectation and nobody noticed | no cost model, or nobody compares | a cost model; compare every run; flag above about twice | 10 |
| The dashboard was green during the incident | no rules for what counts as trouble, or nobody was shown the page | write the trouble list with thresholds and owners; show the page | 10 |
| A prompt or model change broke a decision that used to work | no replay before the change | replay past cases; new versions inherit only the trust their record supports | 11, 13 |
