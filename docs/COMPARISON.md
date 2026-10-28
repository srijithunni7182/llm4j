# llm4j and Loom next to LangChain, LangGraph and LangChain4j

**Status: draft. Written from the other projects' official documentation on 2026-10-28. No probe has been run yet** (the probes are listed at the end, so anyone can run them and check). Where a fact came from a search summary or a page we could not read in full, it says so. Where the documentation says nothing, the cell says "not found", which is not the same as "does not exist".

This is a comparison of *capabilities and effort*, not of answer quality: nothing here calls a live model. Most of what separates these stacks (testing the logic, surviving a crash, stopping before an overspend, reviewing for security, watching a run, shipping it) does not depend on a model, so it can be compared fairly and cheaply.

## Who is compared

| | What it is | Language |
|---|---|---|
| **LangChain + LangGraph** (v1) | The agent framework (`create_agent`, middleware) and its graph runtime (checkpointers, `interrupt`), with **LangSmith** (tracing, evaluation, deployments, Studio) as the commercial platform around it | Python (and JavaScript) |
| **LangChain4j** | The Java library: AI Services, guardrails, and an `agentic` module for workflows | Java |
| **llm4j** (this repository) | `ai-agent4j` (agents), **Loom** (a workflow language and the `weave` CLI), **eval4j**, **Fabric** (observability), the VS Code extension | Java |

## The capability matrix

`✅` built in and documented · `◐` partly, or you build the rest · `—` not found in the documentation we read

| Capability | LangChain / LangGraph (Python) | LangChain4j | llm4j / Loom |
|---|---|---|---|
| **Build the first draft from a description** | ◐ LangSmith *Fleet*: no-code agents from "everyday language" (Plus and Enterprise plans); its Build tab is a configuration form. No generator that plans, builds and gates a code project was found | — | ✅ `weave generate`: plan first, then a project that passes five gates (check, review, tests, coverage, plan fidelity). Our own 30-description benchmark: 27 pass with cheap models (see `docs/specs/12-generate/VERIFICATION.md`) |
| **Visualise the workflow** | ✅ Studio: a free local visual interface (`langgraph dev`; the UI is hosted at smith.langchain.com) | — | ✅ Live graph in VS Code (Outline, Focus, Flow), Mermaid export. Local, no account |
| **Review for security before it runs** | — no static audit of an agent found. PII detection middleware exists (redacts at run time) | ◐ input guardrails incl. a regex prompt-injection guardrail (OWASP LLM01) and moderation (page marks guardrails experimental) | ✅ `weave review` / `weave audit` read the script: the lethal trifecta, unapproved effects, missing budgets; mapped to the OWASP Top 10 for LLM Applications |
| **Test the logic without a model** | ◐ pytest with a fresh in-memory checkpointer per test; single nodes can be invoked. Fake models, generated tests and branch coverage: not found on the testing page | — the testing page says "More information coming soon" and shows one example. Test helpers exist for guardrails only | ✅ `weave test`: scripted models and tools, failure injection, step coverage; the tool writes cases for every branch |
| **Evaluate answer quality** | ✅ LangSmith: datasets, human / code / LLM-as-judge / pairwise evaluators, online evaluation of production traces. Hosted; self-hosting is an Enterprise-plan option | — no evaluation API found | ✅ `weave eval` plus **eval4j**: golden datasets, judges, a local report you can open and share |
| **Human approval before an effect** | ✅ `HumanInTheLoopMiddleware` (approve / edit / reject per tool) and `interrupt()`; both need a checkpointer | ✅ a `HumanInTheLoop` agent (source: a summary of the agents page, not the verbatim text) | ✅ `human_prompt`, `approve:` on a tool, `decide`; the answer is journaled; answer on the console, by a command, or on Telegram |
| **Survive a crash and resume** | ✅ checkpointers (in-memory is RAM only; SQLite and Postgres persist). The resumed node **re-runs from its start**, and the docs say side effects before an interrupt "should (ideally) be idempotent": the developer is responsible | ◐ composite agents are said to continue after a crash (verbatim); an `AgenticScopeStore` with checkpointing after each agent is described in a summary we could not verify; BDI planners "re-deliberate from scratch" | ✅ every step and effect is journaled; resume continues from the journal and **an effect is not repeated** (`docs/security/secure-by-construction.md`). *Probe P1 will check this against LangGraph's behaviour* |
| **Stop before overspending** | ◐ `ModelCallLimit` and `ToolCallLimit` middleware (counts per thread or run). No dollar or per-run token budget was found; token triggers exist only for context summarising | — not found; token usage is reported on results | ✅ `budget { tokens: calls: }` per run and per step, checked **before** each model call; dollars with a price table (`--max-cost`) |
| **PII kept out of the model and the logs** | ✅ PII detection middleware | — not found | ✅ `guard { pii: mask }`; `MaskingLLMClient` in Java |
| **Watch a run** | ✅ LangSmith tracing, dashboards, alerts, "Engine" (hosted; 14-day trace retention on the base tier). Self-hosted and BYOC need the Enterprise plan | ◐ events and listeners following the OpenTelemetry GenAI conventions; Micrometer modules (a summary); no UI of its own | ✅ `--observe` live page and **Fabric** (self-hosted, free): cost, context windows, incidents, injection and trifecta signals, time bar, Pause / Suspend / Stop. OTLP export |
| **Understand or replay a run** | ✅ Studio: breakpoints, edit state, fork, re-run from a checkpoint | — | ✅ `weave why`, `whatif`, `timeline`, `rewind`, `fork`, `replay` |
| **Turn a bad production run into a test** | ◐ LangSmith can build datasets from traces; the step from a trace to a deterministic test was not found | — | ✅ `weave capture` (masked, approved by a person) then `regress` on past runs and `gate` for CI |
| **Deploy** | ✅ `langgraph dev` locally; LangSmith Cloud from a GitHub repo; hybrid and self-hosted for Enterprise. Whether `langgraph build` makes a container image: not stated on the pages read | — | ◐ `weave deploy` writes Docker, Compose or Kubernetes files from the script (text only). No hosted platform |
| **Model providers and integrations** | ✅ the largest ecosystem here (not measured in this document) | ✅ many providers and vector stores | ◐ nine hosted providers and any OpenAI-style server, MCP, OpenAPI, pgvector, Pinecone. **Much smaller ecosystem** |
| **Language and runtime** | Python, JavaScript | Java (the `agentic` module is marked **experimental**) | Java 17+; workflows are written in the Loom language |

## Where the others are ahead, plainly

- **Ecosystem and adoption.** More integrations, more examples, more people who already know it. This is the largest gap and no feature closes it.
- **A hosted platform.** LangSmith gives tracing, evaluation and deployment as a service, with alerts and an online-evaluation loop. We offer self-hosted pieces, not a service.
- **Planner variety.** LangChain4j's `agentic` module lists supervisor, goal-oriented, peer-to-peer, blackboard, voting, debate and BDI planners. Loom takes the other path: a deterministic script with a model inside each step, and no LLM-driven planner.
- **Breadth of middleware.** LangChain's middleware list (tool and model retry, fallback, summarising, to-do lists, sub-agents, shell and file tools and more) is long.
- **Maturity.** Ours is young: the numbers above are from our own benchmarks, and the VS Code experience has not yet been checked by hand.

## Where the evidence points the other way

The capabilities where we found nothing comparable are the **static, model-free ones**: reviewing an agent for the lethal trifecta before it runs, scripted-model tests with step coverage and tool-written cases, an effect that a resume cannot repeat, a budget enforced before each call, one tool from description to deployment files. Those are the claims worth proving with probes.

## Six workflows, and what each exercises

Not every workflow needs every capability, so the fit differs by archetype. These six come from our generation benchmark (`src/loom/ai-agent4j-loom/src/test/resources/generate/descriptions`), which has a recorded passing project for each shape.

| # | Archetype | Capabilities it exercises | Honest fit |
|---|---|---|---|
| A | **A document Q&A or RAG chat** | retrieval, memory, many integrations | **LangChain / LangChain4j lead** on integrations. Loom's value here is small |
| B | **A router**: classify a message, answer or escalate | classification, branching, tests without a model | All three can do it. Loom's edge is exact branching and free generated tests; LangChain4j AI Services is the lightest to write |
| C | **An approval pipeline**: draft, a person approves, send | approval, an outward effect that must not repeat, audit | Loom's edge: journaled effects and a statically reviewed approval path. LangGraph can do it, with the developer making the send idempotent |
| D | **A scheduled, long-running digest** | schedules, durability across restarts, budgets | Loom has schedules, a daemon and `tick`; LangGraph has durable execution and a hosted platform. Whether LangGraph schedules runs on its own was not found in the pages read |
| E | **A research loop**: tools, a critic, bounded rounds | loops with a limit, tool use, context management, cost | A close match: LangGraph has call limits and summarising; Loom has budgets and compaction. LangChain4j has a loop builder |
| F | **Untrusted input plus tools**: a security case | injection, data exposure, approvals, review before running | Loom's edge: a static review of the whole script. LangChain4j has a regex injection guardrail; LangGraph has PII middleware and human approval |

## Probes (planned, not run)

Each probe uses the **same fake model and the same fake tool on both sides**, so it costs no API money and anyone can rerun it.

| # | Question | How it is measured |
|---|---|---|
| P1 | After a crash that follows a send and precedes the next step, does the resume repeat the send? | A fake tool counts calls; kill the process; resume; compare counts (Loom vs LangGraph with a SQLite checkpointer) |
| P2 | Does a budget stop the run **before** the call that would exceed it? | A fake model that reports token usage; a cap just under the second call |
| P3 | Lines and seconds to test a three-way router with no model | Lines of test code written by hand, and by the tool where it writes them; run time |
| P4 | Can a timeout and a retry be tested without waiting? | Inject a failure on call 1 and 2; assert the third attempt; wall-clock time |
| P5 | An approval, then a restart, then the answer | Does the pending question survive the restart on each stack? |
| P6 | Is the lethal trifecta found before running? | One script with untrusted input, private data and an outward tool; what each stack reports statically |
| P7 | Lines of code for archetypes B and C | Count the lines of the hand-written equivalent on each stack |
| P8 | What can you see about a run without a paid service? | List what each stack shows for the same run with only the open-source parts |

## Sources

All read on 2026-10-28.

- LangChain middleware: <https://docs.langchain.com/oss/python/langchain/middleware/overview> (read in full); <https://docs.langchain.com/oss/python/langchain/middleware/built-in> (HTTP 200 but truncated at about 39,000 of 68,000 characters; the entries after "To-do list" came from a summary)
- LangGraph: <https://docs.langchain.com/oss/python/langgraph/interrupts>, <https://docs.langchain.com/oss/python/langgraph/persistence>, <https://docs.langchain.com/oss/python/langgraph/test>. The durable-execution page (<https://docs.langchain.com/oss/python/langgraph/durable-execution>) returned the persistence page's content when fetched, so statements about durability modes come from a search summary of that page and are not verified here.
- LangSmith and Studio: <https://docs.langchain.com/langsmith/evaluation>, <https://docs.langchain.com/langsmith/observability>, <https://docs.langchain.com/oss/python/langgraph/studio>, <https://docs.langchain.com/oss/python/langgraph/deploy>, <https://docs.langchain.com/langsmith/platform-setup>, <https://langchain.com/pricing>
- LangChain4j: <https://docs.langchain4j.dev/tutorials/agents> (the raw source was verbatim for about 39,000 and 40,000 of 246,000 characters; the rest came from a summary, so the human-in-the-loop and persistence details are flagged above), <https://docs.langchain4j.dev/tutorials/guardrails>, <https://docs.langchain4j.dev/tutorials/observability>, <https://docs.langchain4j.dev/tutorials/testing-and-evaluation>, <https://docs.langchain4j.dev/tutorials/ai-services>
- llm4j: this repository: [`THE_STACK.md`](THE_STACK.md), [`security/secure-by-construction.md`](security/secure-by-construction.md), [`specs/12-generate/VERIFICATION.md`](specs/12-generate/VERIFICATION.md), [`COMMAND_REFERENCE.md`](COMMAND_REFERENCE.md)

Documentation changes. Re-check any cell before quoting it.
