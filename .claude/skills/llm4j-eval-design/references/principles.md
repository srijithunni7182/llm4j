# Principles: why testing an agentic system is different

*Distilled, in llm4j terms, from the owner's guide* Quality Assurance for AI agentic systems. *Not a copy: the guide stays the owner's document for human readers. External works it points to: Hamel Husain, "Your AI Product Needs Evals" (https://hamel.dev/blog/posts/evals/); Simon Willison, "The lethal trifecta for AI agents" (https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/); the OWASP GenAI LLM Top 10 (https://genai.owasp.org/resource/owasp-genai-llm-top-10-2026/).*

## What does not change

Test planning, risk-based prioritisation, coverage thinking, boundary analysis, equivalence partitioning, defect life cycle, entry and exit criteria, traceability, and asking "what happens if I do the wrong thing here". Most of an AI product is conventional software (APIs, databases, queues, authentication, persistence), and **50 to 70% of the test effort is conventional testing of conventional parts**, where most early defects are found. In llm4j that part is `weave test` (scripted models, scripted tasks, fault injection, no model, no cost) and ordinary unit tests of your tasks. Do that first. The rest of this skill is about the remaining part that is different.

## The four properties that change everything

1. **Non-determinism.** The same input gives different outputs, by design: a model picks each next token from a probability distribution. That is not flakiness. Flakiness is a test failing for reasons unrelated to the system; here you do not eliminate the variation, you **measure** it. Do not set temperature to 0 for evaluation: it measures a configuration that will not ship and hides the variance users will see. Keep temperature 0 for one narrow purpose: finding out whether an observed difference came from sampling or from a real change. Evaluate at the production temperature and record it.
2. **No expected result.** A diagnostic agent may ask for the logs first, or the route table first; all are competent. There is no single value to assert. This is the **oracle problem**, and constructing a replacement is most of the work (`oracles.md`).
3. **Behaviour is specified by description, not by rules.** The specification is a prompt written in English that the model interprets. Small wording changes cause disproportionate changes in behaviour; behaviour on inputs nobody anticipated cannot be derived from the specification, only observed; there is no code path to read to explain *why*; two prompts that read as equivalent to a person may not behave equivalently. So exploratory testing carries more weight than usual, and reviewing prompts is a legitimate, high-yield static test (`weave explain` and `weave review` read them).
4. **Quality changes without a code change.** A provider updates a model version behind a stable name, someone edits a prompt, a tool description is reworded, the provider silently adjusts behaviour. The repository is untouched, no test is triggered, and the product got worse. Regression runs must be triggered by **configuration changes and by the calendar**, not only by commits.

## The mindset shift

| Conventional | An AI system |
|---|---|
| Does the output equal the expected value? | Is the output within the set of acceptable outputs, and how often? |
| A test passes or fails | A test has a pass rate |
| One execution is sufficient | Multiple executions are required |
| Failure means a defect | Failure may be within normal variance |
| Coverage is over code paths and inputs | Coverage is over scenario space and failure modes |
| Regressions come from commits | Regressions come from configuration, and from the outside world |
| The spec says what correct is | A domain expert says what correct is, per scenario, in advance |

## How these systems work, for a tester

- A model has **no state between calls**. Every request carries the whole conversation. When a session seems to "remember", code assembled that history; bugs in the assembly look like reasoning failures.
- Tokens are the unit of cost and capacity (about 4 characters of English each). Every call is billed in tokens, and every model has a limit on how many it can consider at once.
- The **context window** is a hard limit with a soft failure mode: when accumulated content exceeds it, something is dropped silently and the model reasons as though it still had everything (it re-requests information it already has, or contradicts an earlier conclusion). Test long sessions explicitly; ask what the eviction policy is, and whether the model is told that eviction happened. In Loom this is the `context` and `compact` settings.
- **The system prompt is the specification** of the behaviour. When someone asks "where is the requirement?", the answer is usually the system prompt. Get access to it and read it: it reveals ambiguous, contradictory or impossible instructions.
- **Tool calling**: the model emits a structured request, the *application* executes it, and the result is added to the conversation. The tool description is part of the specification (a reworded description is a configuration change that needs regression testing). Every tool is an attack surface and a defect surface: the model chooses the arguments, so wrong, out-of-scope and injected arguments are all real; verify that the application validates arguments and enforces permissions, never the model.
- **RAG** has two independent failure points, retrieval (wrong or missing documents) and generation (right documents, an answer that ignores them). Test the stages separately: measuring only the final answer sends you to fix a prompt when retrieval was the fault.
- An **agent** is a loop whose termination is a model's judgement, so bounding the loop is a functional requirement, not an optimisation. The **trajectory** is the sequence of steps; it is as much the object of testing as the final answer.
- **Multi-agent** systems add failure modes: routing errors, handoff losses, loops between agents, responsibility gaps, state conflicts. Explicit handoffs (a defined transition) are far more testable than implicit ones, because the legal transitions can be enumerated and asserted; in Loom the workflow's graph is that gift (`weave graph`).
- A **hallucination** is a claim not supported by the input or by reality. Intrinsic (contradicts the source) can be checked automatically; extrinsic (adds detail the source neither confirms nor denies) is harder. Invented specifics (paths, event ids, version numbers) are more convincing than vague falsehoods. **Grounding** is tying claims to supplied evidence; **groundedness** is the measured degree.
- **Prompt injection** is instructions embedded in content the system ingests. There is no equivalent of a parameterised query: the model has no mechanism that guarantees a distinction between instruction and data, so mitigation at the model is probabilistic and the real controls sit **outside the model**. **Jailbreaking** disables existing constraints; injection introduces new instructions; in practice they combine. See `security-testing.md`.
- A **guardrail** is model-level (an instruction in the prompt: probabilistic, can be talked around) or system-level (code outside the model: input filters, output validators, permission checks, allowlists, approval gates; deterministic). Test them differently and never confuse them. The most important security test: *when a model-level guardrail is successfully bypassed, do the system-level controls still hold?*
- **Excessive agency**: the system can act beyond what the task needs. Ask of every feature: **what is the worst thing this system can do if it is completely wrong or completely fooled?** If the answer is worse than "gives bad advice", the permission model is a first-class test target.

## Words to use the same way

An **eval** is a test suite whose runs produce scores (not pass/fail); the **golden dataset** is the curated set of cases with known-correct answers, the test data and the oracle together, and the asset that outlives every model, prompt and framework; **ground truth** is the correct answer recorded in advance by someone qualified; an **LLM judge** is a separate model scoring outputs against a rubric, and is an instrument that must be calibrated; **drift** is quality changing over time with no intentional change; a **trace** is the complete record of one run; a **fixture** is a recorded external response replayed to make a run deterministic; the **configuration fingerprint** identifies exactly what produced a result.

## The do's and don'ts

Do: define what correct means before measuring quality; build and validate the evaluation framework, not just the system under test; measure outcomes statistically; understand the limits and resolution of your metrics; treat safety controls as mitigations unless they are code; test whether human approval gates hold under real and adversarial conditions; build reusable datasets and benchmarks; calibrate judges against humans; separate real signal from variance; require explicit justification before changing a baseline.

Don't: assume every prompt has a single correct answer; treat variability as proof of failure; assume the system is untestable because outputs differ; rely on pass/fail assertions alone; equate 99% reliability with complete safety; assume human reviewers actually review; tie your strategy to one framework or tool; re-baseline to hide regressions; evaluate by impression or anecdote; confuse consistency with correctness.
