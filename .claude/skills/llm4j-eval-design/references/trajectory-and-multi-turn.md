# Trajectory, multi-turn and multi-agent evaluation

*Distilled, in llm4j terms, from the owner's guide* Quality Assurance for AI agentic systems *(part 6.1).*

Single-shot evaluation (one prompt, one response, score it) covers a shrinking fraction of real AI features. Agent systems are multi-turn and often multi-agent, and they fail in ways single-shot evaluation cannot see.

## Outcome evaluation is not enough

Two runs that both reach the correct conclusion:

| | Run A | Run B |
|---|---|---|
| Tool calls | 4 | 17 |
| Redundant calls | 0 | 6 |
| Tool errors | 0 | 2, both ignored |
| Agent handoffs | 2 | 9 |
| Cost | $0.22 | $1.85 |
| Duration | 40 s | 6 min |
| Final answer | correct | correct |

Outcome evaluation scores these identically. Run B has at least four defects (redundant calls, silently swallowed errors, handoff thrashing, a cost overrun), and in production it is the one that will occasionally not terminate. **On agent systems, evaluate the trajectory as well as the outcome.**

## Trajectory: four properties

**Legality (deterministic).** Every transition is permitted. If you have a state graph, enumerate the legal edges and assert against them; if handoffs are implicit, push for explicit ones, which converts a judgement call into an assertion. Assertions: no illegal transition; no transition from a phase that does not permit it; no agent activated without its precondition. In Loom the graph is the workflow itself: `weave graph` lists its steps and edges, and a test names the steps a run must take or never take (`weave test --steps` lists the names; `expect: route`, `route_exact`, `never`).

**Efficiency (deterministic).** No duplicate tool call with identical arguments; no request for information already in state; no handoff cycle exceeding N iterations; step count within the case's expected band.

**Error handling (deterministic, and the highest-yield check here).** **Every tool error must be visibly handled.** The characteristic agent failure is calling a tool, receiving an error, and continuing as though it succeeded, producing a confident answer built on a missing input. Test by fault injection: make a tool fail during a normal run and assert the system either retries, works around it, or reports the failure. **Silent continuation is a defect every time.** In Loom, `weave test` injects faults into a scripted run (`fail:` with a category such as `timeout`, `tool_error`) and the script decides with `retry`, `on_failure` and `halt`.

**Appropriateness (rubric).** Whether the path was sensible: did it investigate before concluding, hand off for the right reasons, respond to what it learned? Rubric dimensions that work well, with anchors at 1, 3 and 5:

| Dimension | 1 | 3 | 5 |
|---|---|---|---|
| Step justification | steps unrelated to the goal | mostly relevant, some redundant | every step advances the task |
| Adaptation | plan unchanged regardless of results | adjusts occasionally | consistently revises approach based on what was learned |
| Termination | stops arbitrarily early or continues past sufficiency | roughly appropriate | stops precisely when sufficient, states why |

## Turn level versus session level

| Level | Question | Metrics |
|---|---|---|
| Turn | Was this individual response appropriate given everything so far? | groundedness, relevance, instruction following, tone |
| Session | Did the whole interaction achieve the goal, well? | task completion, turns to completion, total cost, coherence, user corrections required |

A common trap: every turn scores well individually while the session fails. Each response is helpful, none moves toward resolution. **Turn-level metrics cannot detect this; task completion rate and turns-to-completion can.**

## Building multi-turn cases

The input at turn 5 depends on the output at turn 4, which varies between runs. Three approaches, with trade-offs; **use all three**:

| Approach | Method | Strength | Weakness |
|---|---|---|---|
| Scripted | fixed user turns regardless of what the system says | fully reproducible | turn 3 may be nonsense if the system went a different way |
| Simulated user | a model plays the user, with a persona, a goal and stopping conditions | realistic, adaptive | adds a second non-deterministic component; the simulator needs its own validation |
| Recorded | replay real conversations from production | highest fidelity | only tests paths already taken; no coverage of new behaviour |

Scripted for regression (reproducibility matters most), simulated for exploration and robustness, recorded for realism and for validating that lab results match the field. **If you use a simulated user, validate it:** it is a component of your test apparatus, and an unvalidated simulator that behaves unrealistically produces confident, meaningless results. Sample its conversations and have a human confirm they resemble real user behaviour. In llm4j a scripted multi-turn case is `weave test` with `humans:` answers; a recorded one is `weave capture`.

## Multi-agent specific tests

| Failure mode | Test |
|---|---|
| Routing error | a labelled corpus of (message, state, phase) to expected agent. Report a **confusion matrix**, not a single accuracy figure: it shows *which* confusion is growing, and some confusions cost far more than others |
| Context loss at handoff | plant a fact before a handoff; assert the receiving agent has it |
| Handoff thrashing | construct a case where two agents can bounce work; assert a bound exists and fires |
| Responsibility gap | inputs that fit no agent cleanly; assert graceful handling rather than silence or an arbitrary route |
| State conflict | force concurrent state writes; assert no lost update |
| Privilege leakage | assert an agent's permissions do not widen when work is handed to it from a more privileged agent |

The routing confusion matrix deserves emphasis. Mis-routing is asymmetric: sending an information-gathering request to an action-taking agent is far worse than the reverse, because it skips the gathering that would justify the action. **Gate on the specific dangerous cells, not on aggregate routing accuracy.** In Loom, routing by `intentclassify` or by an agent's answer is tested with `weave test` (scripted classifier answers) and measured by `weave eval` on the classifier's own scenarios.

## Context accumulation

In a long session state grows (conversation, retrieved documents, tool results, intermediate conclusions) and eventually exceeds the context window. Test explicitly:

| Test | Expected |
|---|---|
| Run sessions past the point of eviction | a defined, predictable eviction order |
| Check whether the model is informed | an explicit marker that content was dropped, and what class of content |
| Check retrievability | evicted content still addressable and re-fetchable by reference |
| Check for a hard limit | past a threshold, the session checkpoints and summarises rather than continuing to shed content |
| Check coherence at depth | conclusions do not silently rest on evicted material |

**Silent eviction is one of the highest-severity untested defects in most agent systems**, because the symptom (a system that contradicts itself late in a long session) is usually misdiagnosed as a reasoning problem and addressed with prompt changes that cannot fix it. In Loom see `context` and `compact`, and the context-window chapter of `weave guide`.
