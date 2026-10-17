# The design record: `HARNESS.md`

Write it at the project root, with the user, one section per stage. Fill every section; write "none" or "not needed, because ..."
rather than dropping one. Put the user's decisions in their words. Mark anything still open as **Open** with who decides it.

```markdown
# <workflow>: harness design

Version <n>, <date>. Decided by <names>. Built from this record by the llm4j-workflow-guide skill.

## 1. The job
- In: <what goes in>
- Out: <what comes out, and to whom>
- Length of a run: <seconds / hours / days>; unattended: <yes / no>
- Cannot be undone: <the irreversible actions>
- Shape: <pipeline / router / planner and workers / open loop / earned autonomy, per part>, because <why a simpler shape would not do>

## 2. Done, and who checks it
| Exit | Criterion | Checked by (code / model / person) | Result the caller reads |
|---|---|---|---|
| Succeeded | | | |
| Gave up after its bounds | | | |
| Stopped by a person | | | |
| Halted | | | |
| Failed | | | |

Hard requirements: <each one, marked checked by code or judged by a model>
Judge: <model and prompt>, different from the doer because <why>; noise measured on <cases>.

## 3. Boundary
| Agent or tool | Reads | Changes | Sends out | Reach | Untrusted input? | Private data? |
|---|---|---|---|---|---|---|

Locked surfaces (no agent may change them): <objective, evaluator, tests, budget, autonomy, journal, ...>, protected by <how>
Lethal trifecta: <none / split as ... / accepted by <who>, on <date>, because <reason>>
Secrets: <names, where they are stored, bound to which host>
Personal data: <where it is masked or blocked>

## 4. Control loop
| Loop, rewind or budget | Maximum | When reached |
|---|---|---|

Budgets: provider limit <...> > run <...> > per agent or stage <...>
Behaviour rules: <looping, failing tool, context nearly full, too many steps: and the response>
Limits that lift: <rate limits, quotas, refilling budgets: suspend, max wait, max resumes>
| Fan-out | Width bound | If one branch fails or is stopped | Checkpoint before / combined check after |
|---|---|---|---|

## 5. State, memory and context
- Journal: <where; what is recorded>
- Crash before the first effect: <...>; between "about to" and "done": <...>; after the last effect: <...>
- Effects and their idempotency keys: <...>
- Checkpoints: <names, and what a rewind to each carries>
- Progress artifact for long runs: <where; what it holds>
| Agent | Sees | Loops on tools? | Window (source) | Compaction |
|---|---|---|---|---|

## 6. Failure and recovery
| Step | Can fail by | Decision |
|---|---|---|

## 7. People in the loop
| Action | Approver | Channel | If nobody answers |
|---|---|---|---|

Steerable agents, and who may steer: <...>
What a stop means, per step: <...>
Earned autonomy: <not used / decision, ladder, ceiling, who signs off promotions>
Freeze everything: <how, and who may>

## 8. Watching and cost
- Cost model: <calls per run> x <tokens per call> x <price per model> = <expected per run>, <per day>; source of the prices: <...>
- Trouble, with thresholds and who is told: <...>
- Where the trace, journal and audit log are kept, and for how long: <...>
- Who has been shown the page: <...>

## 9. Harness test plan
| Fault injected | Expected behaviour (from sections 4 to 7) | Free on a scripted model? |
|---|---|---|

Held-out cases: <where; who runs them>
Expected path and call count pinned for: <inputs>

## 10. Open questions and accepted risks
- <question> — decides: <who>, by: <when>
- <risk accepted> — by <who>, because <why>

## 11. For the build (llm4j-workflow-guide)
| Section | Feeds stage |
|---|---|
| 1, 3 | 1. Decide the agents |
| 2 | 2. Golden dataset; 11. Build the test examples with the user |
| 3 | 7. Validate and audit |
| 4, 6 | 6. Build the workflow |
| 5 | 6. Build the workflow; 14. Context and compaction |
| 7 | 13. Earned autonomy; 15. Steer, stop and supervise |
| 8 | 9. Go live; then the llm4j-loom-deployer skill |
| 9 | 8. Trajectory tests |
```
