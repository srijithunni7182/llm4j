# Phase 37: decisions

Status: **final: all nineteen decisions taken by the owner** (D1 to D7 in the discussion that started this phase, D8 to D19 on 2026-11-17). Each gives the question, why it matters, the options, the recommendation and, under **Decision**, the owner's answer. [PRINCIPLES.md](PRINCIPLES.md) holds the principles each option is checked against; [DESIGN.md](DESIGN.md) holds the facts.

## Decision log

| | Decision | State |
|---|---|---|
| D1 | The Guide guides; it does not build | **taken** |
| D2 | VS Code first | **taken** |
| D3 | Who the Guide is for | **taken** (a Java developer new to llm4j; teaches more when needed) |
| D4 | Explanations specific to the context | **taken** (generated each time; the fixed-facts rule of principle 45 is proposed with it) |
| D5 | The thorough path is the default | **taken** (shortcuts on insistence; say what is missed) |
| D6 | Show off the stack | **taken** (warranted; the honesty rules of principle 46 are proposed with it) |
| D7 | Which model runs the Guide | **taken** (the developer's existing Copilot or Claude subscription; no new bill) |
| D8 | Two hosts, and the Claude host as its own session | **taken** (as recommended) |
| D9 | How the Guide speaks up: notices | **taken** (notices and a status bar count) |
| D10 | One tool contract, and how each host reaches it | **taken** (one contract, two bindings) |
| D11 | The editor's minimum version | **taken** (raise the minimum for the whole extension) |
| D12 | The size of the project summary | **taken** (as recommended) |
| D13 | A `design-harness` stage | **taken** (always shown) |
| D14 | Where the learner record lives | **taken** (as recommended) |
| D15 | No telemetry | **taken** |
| D16 | Names | **taken** (`guide-context`, `guide-facts`, `guide-mcp`) |
| D17 | The workflow's first runs cost nothing | **taken** (Ollama mentioned as a free option, the developer chooses) |
| D18 | How the Guide itself is evaluated | **taken** (recorded in the build, live on request) |
| D19 | The skip list and the README line | **taken** (as recommended) |

## D1. The Guide guides; it does not build

**Why.** A companion that also edits files competes with the builder, inherits its habit of rushing, and is busy when the developer has a question.

**Decision (owner): it only guides.** It explains, shows, asks, answers questions and steers the builder through instructions the developer sends. It has no tool that writes, runs an effect or spends (principle 41).

## D2. VS Code first

**Decision (owner): VS Code.** The graph, the review panel, the eval report and the run page are already there, and the Guide's value is showing them at the right moment. The Java spine keeps a terminal Guide possible later (DESIGN §7).

## D3. Who the Guide is for

**Decision (owner): mostly a Java developer new to llm4j.** Skip Java basics and lead with the llm4j way. The Guide notices when the developer is also new to agents, workflows or evaluation and teaches more (principle 47, R15).

## D4. Explanations specific to the context

**Why.** Text written once, the same for every project, reads like a manual and is not a conversation. Text generated freely can be wrong about llm4j.

**Decision (owner): context-specific, generated each time.**
**Proposed with it:** the facts are fixed and the wording is free (principle 45). The stage texts (R1) and the facts catalogue (R6) are the only source of claims about llm4j. They are tested against the code and the documentation, and the Guide words them for the project in front of it.

## D5. The thorough path is the default

**Decision (owner): the goal is a well-tested, evaluated, cost-effective, durable, production-ready workflow, not speed.** A shortcut is taken only when the developer insists. When they skip, they are told what they will miss (principle 43, R3). The builder's skills follow the same rule (R20, R21).

## D6. Show off the stack

**Decision (owner): some self-promotion is warranted.** The developer should come away appreciating what llm4j does for them.
**Proposed with it:** promotion through feature moments (R7). A feature is brought up when it solves the problem in front of the developer, shown working on their workflow, and compared with plain Java. It is never claimed beyond what it does (principle 46), and a scenario test fails on an overclaim (TEST_STRATEGY §4).

## D7. Which model runs the Guide

**Why.** A Guide that needs its own provider key bills the developer before they have seen any value, and needs a key before the stage that teaches keys.

- **A.** The extension calls a provider with a key from the editor's secret storage.
- **B.** The developer's existing assistant: Copilot through the editor's language model API, or Claude through Claude Code.
- **C.** A local model through Ollama.

**Decision (owner): B.** Many developers already pay for Copilot or Claude, and they should be able to use either. A is rejected because of the upfront billing. C is not the Guide's model (too weak for this job on most laptops), but it is mentioned to the developer as a free option for the workflow's first real runs (D17).

## D8. Two hosts, and the Claude host as its own session

**Why.** The Guide must stay free while the builder works (D1). On the Claude side, a subagent inside the builder's conversation would share its turn and its context.

- **A.** Copilot: a chat participant `@loom`. Claude: a guide agent run as **its own Claude Code session** next to the builder's.
- **B.** Copilot as above. Claude: a subagent the builder calls.
- **C.** Copilot only, in this phase.

**Recommendation: A.** A separate session keeps the separation that D1 asks for. Mixing is allowed: a Copilot Guide with a Claude Code builder, or the other way round. The checkpoint tool (R14) works for any builder that has the MCP server. Whether the Claude Code VS Code extension can open a session with a given agent is item G4. If it cannot, **Open the Guide** copies a start prompt (R17).

**Decision (owner): A.** Copilot gets the `@loom` chat participant; on Claude Code the Guide is its own session next to the builder's. Mixing hosts is allowed.

## D9. How the Guide speaks up: notices

**Why.** Both hosts are turn-based: neither can speak until the developer sends a message. A Guide that only answers questions will miss the moments that matter.

- **A.** Notices. The extension decides, from a rule table, when an event is worth a conversation, and shows a non-modal message with **Ask the Guide** (R10).
- **B.** A status bar item with a count of undiscussed notes, and no messages.
- **C.** Nothing proactive.

**Recommendation: A, with B as well.** The notice is the moment and the count is the memory. The developer stays in control: one notice every 2 minutes at most, none while they type, and a setting to switch them off.

**Decision (owner): A with B.** Notices with **Ask the Guide**, under those limits, and a status bar count of undiscussed notes.

## D10. One tool contract, and how each host reaches it

**Why.** Two hosts with two tool sets would drift, and the Guide would behave differently on each.

- **A.** One TypeScript contract in the extension.
  - Copilot reaches it through language model tools registered by the extension.
  - Claude Code reaches it through a stdio MCP bridge, `weave guide-mcp`, launched by Claude Code from the plugin's configuration. The bridge serves the Java parts itself (journey, summary, facts, moments) and forwards the editor parts (events, show and steering tools) to the extension over loopback, using a per-session token (DESIGN §5).
- **B.** An MCP server only, for both hosts. This is possible if the editor lets a chat participant, or Copilot's agent mode, use an extension's MCP server (item G2).
- **C.** Two separate tool sets.

**Recommendation: A now, moving to B if G2 shows it works for a participant.** A contract test asserts that both bindings expose the same tool names, parameters and results (TEST_STRATEGY §3).

**Decision (owner): A.** One contract with two bindings: language model tools for Copilot, the `weave guide-mcp` bridge for Claude Code. Moving to B later is allowed if G2 shows it works.

## D11. The editor's minimum version

**Why.** The extension needs `vscode ^1.85.0` today. The chat participant and language model tool APIs need a newer editor (from memory, not read: chat participants around 1.90, language model tools around 1.95; item G1).

- **A.** Raise the minimum for the whole extension.
- **B.** Keep the minimum. Turn the Guide on only when the APIs exist, and say why when they don't.

**Recommendation: B**, if the manifest and the bundling allow a feature that is present only when the API is. Otherwise A, with the new minimum written in the CHANGELOG.

**Decision (owner): A.** Raise `engines.vscode` for the whole extension to the version the chat participant and language model tool APIs need (the exact number is found in G1). It is written in the CHANGELOG, the extension README and `docs/VERSION_MATRIX.md`, and the code needs no checks for missing APIs.

## D12. The size of the project summary

**Recommendation:** at most 4,000 words. Findings beyond the top ten are counted, not listed, and runs beyond the last three are counted. The Guide asks a specific tool for more. This keeps each turn small on the developer's quota (DESIGN §8 risk 5).

**Decision (owner): as recommended.**

## D13. A `design-harness` stage

**Why.** The owner designs the harness first (`HARNESS.md`, the `llm4j-harness-design` skill), but the journey has no stage for it, so neither the Guide nor `weave next` would ask for it.

- **A.** An optional stage, shown only for a workflow the review classes as autonomous (R4).
- **B.** Always shown.
- **C.** No stage; the Guide brings it up as a moment.

**Recommendation: A.** A one-shot extraction pipeline does not need a harness document, and a loop that runs overnight does.

**Decision (owner): B, always shown.** Every workflow gets a `design-harness` stage, done when `HARNESS.md` exists. For a simple workflow the harness design is short, and the Guide says so; a developer who does not want it skips it like any other stage (R3), and the skip says what is lost.

## D14. Where the learner record lives

**Recommendation:** what the developer knows goes in the editor's global state, because it travels with the person. What was discussed in this project goes in `.loom/guide/learner.json`, which is git-ignored. It is shown on request, can be reset, and is never sent anywhere except as part of the Guide's own context.

**Decision (owner): as recommended.**

## D15. No telemetry

**Recommendation:** none. Nothing about how the Guide is used leaves the machine except the host model calls the developer's own assistant makes. Usability is learned from sessions with real developers (M3), not from data collection.

**Decision (owner): no telemetry.**

## D16. Names

**Why.** `weave guide <topic>` already opens documentation, and the extension already has a `guide/` folder for setting up the coding agent.

**Proposal:**
- The feature is **the Guide** (in the editor: "Loom Guide").
- The Copilot participant is `@loom`.
- The Claude agent is `llm4j-guide`.
- The new commands are `weave guide-context`, `weave guide-facts` and `weave guide-mcp`, which keeps `weave guide <topic>` as it is.
- The editor commands are `loom.guide.open` and `loom.guide.notices`.

**Decision (owner): the proposal.** `weave guide-context`, `weave guide-facts` and `weave guide-mcp`; the Guide, `@loom`, `llm4j-guide`, `loom.guide.open` and `loom.guide.notices`. `weave guide <topic>` is unchanged.

## D17. The workflow's first runs cost nothing

**Recommendation:**
- Until the `preflight` stage, the Guide uses scripted models (`weave test`), mock runs and `weave try`.
- For the first runs that need a real model, it offers a local Ollama model when one is installed, and says what that proves and what it does not: quality differs from the hosted model.
- The hosted model's key is set at the preflight stage, with a budget and a cap set before it (principle 48).

**Decision (owner): free by default; Ollama is the developer's choice.** Ollama means installing it and pulling a model, which is work the developer may not want. So the Guide does not steer them to it. When the first real run comes, it says that a local Ollama model is a free option, what installing it takes, and what such a run proves and does not prove, and then the developer chooses between Ollama and a hosted model under a cap. Until then, scripted models, mock runs and `weave try` keep everything free.

## D18. How the Guide itself is evaluated

**Recommendation:** a golden dataset of Guide scenarios, judged with eval4j (TEST_STRATEGY §4).
- The default build runs it against recorded answers.
- A live run against a real model is `@Tag("live")` and run only when the owner asks.

This also makes the Guide a showcase of eval4j on itself, which D6 likes.

**Decision (owner): as recommended.** Recorded answers in the default build; re-recording against a real model only when the owner asks. No live run in CI.

## D19. The skip list and the README line

**Recommendation:** add `.loom/skipped.json` with one entry per stage (R3). Keep reading the README line `Evaluation: skipped` as a skip of stages 2, 3 and 9, so existing projects and the skills' instructions keep working. `weave next --skip` also writes the README line when the stages skipped are exactly those three.

**Decision (owner): as recommended.**
