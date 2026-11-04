# Architecture you can review

*Source: [Architecture you can review](../REVIEWABLE_ARCHITECTURE.md), which has the scripts and the exact output of every check below. Everything here is on that page.*

Loom distils multi-agent best practice into a language that can be reviewed, by people and by tools. In code-first frameworks the architecture is a convention; in Loom it is checked.

## Why a language

In a code-first framework the workflow is general-purpose code. Which step sees what, which call changes something, where a person is asked and what is checked before a value leaves are all spread through the plumbing. A reviewer has to read the plumbing, and a tool cannot analyse arbitrary code. We ran the same nine scenarios with fake models on LangGraph 1.2.18, LangChain 1.4.7 and the Python Google ADK 2.13.0. In each one the framework offered a hook, and in none did anything read the workflow before the run and say the hook was missing ([the probes](../../probes/README.md#p9-to-p17-the-architecture-mistakes-on-loom-langgraph-langchain-and-google-adk)).

In Loom the workflow is a small language, so a tool can know before anything runs which agents and tools exist, what each tool does to the world, where a value written by a model can flow, and where a value leaves.

## Thirteen mistakes, in brief

1. **The god prompt.** A rule that lives only in a prompt. In Loom the rules sit in a policy file, an evaluator checks the exits, and `weave check` refuses an exit with no check. In the probe a rule-breaking reply left with no check in every framework ([P11](../../probes/README.md#p11-a-reply-that-breaks-a-written-rule-can-it-leave-without-a-check)).
2. **The JSON trap.** An agent that must think and also fill a large nested schema. Review rule LP10 reports it; the fix is prose, then an `extractor`. None of the three frameworks noticed before the run ([P10](../../probes/README.md#p10-a-step-that-uses-tools-and-must-answer-in-a-large-nested-schema)).
3. **Evaluation as an afterthought.** In Loom the golden dataset is the requirements, and an evaluator or extractor with no cases does not pass `weave check`. A guard with no test built and ran without a word in all three ([P13](../../probes/README.md#p13-a-guard-that-enforces-a-rule-can-it-be-used-with-no-test-of-it)).
4. **Asking a person mid-reasoning.** Ask once at the edge with `use ... approve`. LangGraph's `interrupt()` inside a node restarted the node, so the text sent was not the text shown; LangChain and ADK ask at the tool call ([P12](../../probes/README.md#p12-a-sending-tool-that-needs-a-persons-yes-where-does-the-run-pause)). A tool that changes something is declared once, and `weave check` refuses a retry on it; a tool that posted and timed out was posted twice under each framework's retry ([P14](../../probes/README.md#p14-a-tool-that-posts-is-it-changes-something-stated-once-and-honoured)).
5. **Global state leakage.** Internal nodes see only their inputs. In ADK, `include_contents='none'` still let the previous agent's output through in a `SequentialAgent`; an `AgentTool` did not ([P9](../../probes/README.md#p9-a-utility-step-that-pulls-fields-out-of-text-can-it-be-given-only-its-inputs)).
6. **Premature agentification.** A model asked to do arithmetic. Tasks and classifiers are first-class, and LC07 reports the wording. No probe, no claim about other frameworks.
7. **Error cascading.** One agent's invented amount reaching an agent that pays. LR11 reports it; extract, check or approve before the step acts. In all three frameworks it reached the tool with no warning ([P15](../../probes/README.md#p15-one-agents-invented-amount-handed-to-an-agent-that-pays)).
8. **Stringly-typed state.** A long job whose progress lives in a transcript that may be compacted. In Loom the script owns typed progress (starter 16). All three frameworks can keep progress outside the messages, with a field or tool you write ([P16](../../probes/README.md#p16-a-long-agent-loop-that-runs-past-its-context-window)).
9. **Unbounded action loops.** Loops, retries and budgets are bounded and reported when they are not (LR03). No new probe.
10. **The model chooses the route.** A planner told to route a task to a privileged agent. Loom requires an allow-list, and refuses a value outside it. LangGraph's `Command(goto=...)` ran the privileged node while a conditional edge's target list refused it; a LangChain tool list and an ADK `sub_agents` list are allow-lists by construction ([P17](../../probes/README.md#p17-a-planner-told-to-route-a-task-to-a-privileged-agent)).
11. **Unbounded fan-out.** `for each ... max N`; a longer list fails and points upstream (LR13).
12. **The doer grades itself.** A loop that ends when the writer says it is done (LR12), an evaluator on the same model as the text it checks (LR14), and a different default judge in `weave eval`.
13. **Self-reported confidence.** A number a model writes about its own answer is not a calibrated probability. It is taught, not checked.

## What is not enforced

Whether an agent's words reach people, and what a prompt means, are judgements a language cannot make. A Java task is trusted code. A declared effect is the developer's word. The checks read structure: they do not know whether a policy's rules are the right rules or whether a golden case is a good case, which is why `weave eval --requirements` puts both in front of a person.

Every probe uses fake models and tools, costs no API money, pins its versions and is rerunnable: `probes/run-frameworks.sh`.
