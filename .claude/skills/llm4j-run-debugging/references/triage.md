# Symptom, cause, first command

Start from what the person says. Every command below is free unless marked. `<run>` is the run folder (`--journal`) or a run id with
`--store`.

| The person says | Usually | First command | Then |
|---|---|---|---|
| "It gave a wrong answer" | a wrong value in: a model's answer, a tool's result, or an input | `weave why <run>`: find where the wrong value was bound and by which step | check that step's prompt and input; reproduce with a `whatif --answer Agent=...`; write the test |
| "It took the wrong branch" | a wrong choice: the condition, the classifier's label, or a missing label | `weave why <run> --line N` for the `alt` | change the condition or the labels in the script, not the wording of a prompt; add the confusable case to the golden dataset |
| "It stopped" or "it crashed" | a failed step with no `on_failure`, or a budget stop | `weave timeline <run>` then `weave why <run>` | add `retry` and `on_failure` where the failure is expected; `weave reset <run> --failed --reason ...` tries only the failed steps again |
| "It went another way than I expected" | a step failed and its `on_failure` took over | `weave why <run>` (it shows the diverted step) | decide whether that is what should happen; make the `note` say so |
| "It keeps looping" or "it never finishes" | a loop with a condition that cannot become true, a retry storm, or an agent calling the same tool again | `weave run <script> --trace` (spends, ask first) or `weave why <run> --all` on a run that stopped | check the loop's `max` and `on_exhausted`; look at the tool's result the agent keeps getting; add a limit |
| "It is stuck" or "it is waiting" | a question nobody answered, a `sleep`, or a timeout | `weave questions` (lists what waits for a person) and `weave timeline <run>` | `weave answer` it; or `weave tick` / `weave daemon` is not running |
| "It is slow" | a slow provider or tool, a huge prompt, or work that could run in parallel | `weave timeline <run>`; on a running process, the Tools and Context views (`llm4j-loom-deployer`) | shorten what tools return; `parallel` for independent steps |
| "It is expensive" | a prompt that keeps growing, a loop that re-sends everything, or a bigger model than the step needs | `weave timeline <run>` for the cost per step | add a `context` block; split the job; reconsider the model (`llm4j-workflow-guide`, chapter 16) |
| "It forgot what I told it" | compaction or a long transcript | `weave run --trace` shows context and compaction events | a `context` block the person agreed to; a typed progress value the script owns (the step-by-step pattern) |
| "It worked yesterday" | a changed script, prompt, model or tool result; or a changed provider | `weave why <run> --script <yesterday's script>` and `weave regress <script> --store <dir>` on past runs | compare against the recorded runs: unsafe changes come first |
| "Why did it send that?" | an effect whose approval or condition was not what the person thought | `weave why <run>`, then the step that sent | the approval text must be the exact text sent; check the `effect:` mark and the approval path |
| "A child went wrong" or "the supervisor did something" | a started workflow (`start`) is a run of its own: its status, its supervise rules and its judge each leave a record | `weave timeline <run>` draws the children as a tree; `weave why <child folder>` for one child; in the parent's journal the `child`, `supervise`, `judge` and `child-cancel` entries say what fired and why | read `<handle>.status` and `<handle>.reason`; a rule fires when it becomes true, a judge is bounded by `max` and its own budget; `weave fork` copies a run with its children |
| "Is it safe?" | the lethal trifecta, an injection, a tool that reaches too far | `weave audit <script> --fail-on medium` | `llm4j-workflow-guide`, stage 7; `llm4j-loom-deployer` for the live security view |

## What to say when you cannot tell

- "There is no journal for this run, so I cannot say which choice it made. I can re-run it under a cap with a journal and a trace; it will cost
  about X. Shall I?"
- "The script has changed since this run, so `why` would explain a different script. Which version did it use?"
- "`whatif` stopped: the change reaches this prompt and there is no recorded answer for the new question. It needs the real model, or
  an answer you supply with `--answer`."
- "I fixed the branch. I have not shown the answers are better with a real model; that is `weave eval`."
