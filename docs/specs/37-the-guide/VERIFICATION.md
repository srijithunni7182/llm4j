# Phase 37 verification plan: the Guide

Status: **final (all decisions taken); nothing built.** Section 0 lists what was read and what is still to read. Section 1 lists what must be proved. Companions: [DESIGN.md](DESIGN.md), [TEST_STRATEGY.md](TEST_STRATEGY.md), [DECISIONS.md](DECISIONS.md), [PRINCIPLES.md](PRINCIPLES.md).

**Evidence levels:**
- E0: read in the source or the documents.
- E1: a unit test.
- E2: a scenario evaluation against recorded answers.
- E3: a host walked end to end in a real editor by the owner.
- E4: a usability session with a Java developer new to llm4j.

The default build needs no model and no key. `make test-live` is never run unasked.

## 0. Survey

### Read (E0)

- **The journey:** `src/loom/ai-agent4j-loom/src/main/java/io/github/llm4j/loom/cli/JourneyStages.java` (ten stages, the README skip line, `.loom/preflight.json`, `.loom/confirmed.json`, `.loom/eval.json`).
- **The extension:**
  - `src/loom/vscode-loom/package.json`: commands, the walkthrough `loom.gettingStarted`, the Outline view, engine `^1.85.0`, and no chat participants or language model tools;
  - `src/views/progressModel.ts` (the journey in the Outline, `loom/next`);
  - `src/commands/onboardingCommands.ts` (the hand-off to the agent chat);
  - `src/guide/agentOffer.ts` and `src/guide/agentSkills.ts` (setting up the coding agent; skill folders `.agents/skills`, `.claude/skills`, `.github/skills`).
- **The skills:** `.claude/skills/llm4j-workflow-guide/SKILL.md` (the fast lane offered once, and the key rules) and the list of skills.

### To read before the work that depends on it

| # | What | Why | When |
|---|---|---|---|
| G1 | The VS Code chat participant and language model tool APIs: the minimum version to raise `engines.vscode` to, how a participant gets a model, the behaviour with no model (read; see notes) | D11, R16 | 37c, first |
| G2 | Whether a chat participant, or Copilot's agent mode, can use an MCP server registered by an extension | D10 option B | 37c |
| G3 | The Claude Code plugin format: an agent with a tool allow-list, MCP server configuration, how a plugin is installed from a VS Code extension | R17 | 37d, first |
| G4 | Whether the Claude Code VS Code extension can open a new session with a named agent, from a command or a link | D8, R17 | 37d, first |
| G5 | **The terms of GitHub Copilot and of Claude subscriptions for an extension that uses them through their own extensions,** and anything they forbid (for example automated use, or use outside the editor) | D7; a Guide that broke the terms would be withdrawn | before 37c |
| G6 | How the engine (`weave serve`) is reached from the extension today, and whether `weave guide-mcp` can reuse its protocol for the Java parts | D10 | 37b |
| G7 | How the review classes a workflow as autonomous (a loop, schedule, `supervise`, daemon, effects with no approval), so that the `design-harness` stage's text can say whether this workflow needs a short or a full harness design | R4, D13 | 37a |
| G8 | Where the eval4j report is written and how the extension opens it today | R12 | 37b |

### Notes from the reads (written by the build; each says when it was read)

- **G1 (2026-11-17).** VS Code's chat guide: "Chat participants are deprecated and are not supported in the Copilot harness. The Chat Participant API remains available for Local harness compatibility." The Local harness is due for removal in February 2027. No page states a minimum version; `@types/vscode` shows `lm.registerMcpServerDefinitionProvider` first in **1.101** (absent in 1.100). Language model selection returns an empty list when no model is available, and Copilot models need the user's consent. **Consequence:** D8 and D10 changed (no participant; an agent and one MCP server), D11's minimum is 1.101.
- **G2 (2026-11-17).** Extensions contribute `mcpServerDefinitionProviders` and call `lm.registerMcpServerDefinitionProvider`. Copilot custom agents live in `.github/agents/<name>.agent.md` (user level `~/.copilot/agents`), with `name`, `description`, `tools`, `model` and more in the front matter; MCP tools are named `<server>/*`. No page says that tools left out of `tools` are unavailable, or that an extension can contribute a custom agent, so the extension writes the file into the workspace and M1 checks the allow-list by hand.
- **G3 (2026-11-17).** A Claude Code plugin is a folder with an optional `.claude-plugin/plugin.json` and `agents/`, `skills/`, `.mcp.json`. Plugin agents take `tools` (allow-list) and `disallowedTools`, and ignore `hooks`, `mcpServers` and `permissionMode`; MCP tools are named `mcp__<server>__<tool>` (wildcards allowed); `${CLAUDE_PLUGIN_ROOT}` is expanded in a stdio server's command, arguments and environment.
- **G4 (2026-11-17).** `claude --agent <name>` (or `"agent"` in settings) starts a session as an agent. The VS Code extension opens with a pre-filled prompt (`vscode://anthropic.claude-code/open?prompt=...`) but has no way to name an agent. **Consequence:** "Open the Guide" runs `claude --agent llm4j-guide` in an editor terminal.
- **G5 (2026-11-17).** Anthropic: third parties may not offer Claude.ai login in their own applications or collect or relay Claude.ai credentials; users may sign in to the unmodified Claude Code with their own subscription. GitHub's additional-products terms say nothing about extensions and Copilot; section J of GitHub's terms was not read. **Consequence:** with D10 changed, our code calls no model and handles no sign-in, so the Guide is an agent definition and tools run by each assistant as its own user. Section J is still to be read before release.
- **G6 (2026-11-17).** The extension reaches `weave serve` over a framed JSON protocol (`engine/protocol.ts`, `loom/next` among its methods). `weave guide-mcp` does not reuse it: it is a separate stdio MCP server run by the assistant, which reads the project itself.
- **G7 (2026-11-17).** The review has no single "autonomous" classification (the audit speaks of autonomy only for decision ladders). The `design-harness` stage computes its own small reading of the script: schedules, `supervise`, loops, and tools with `changes` effects and no approval.
- **G8 (2026-11-17).** `weave eval` writes its bundle under `target/eval4j` (Maven project) or `eval-runs`, and the eval4j report renders it to `report/index.html` there; the extension has no command that opens it today (`loom.eval` types `weave eval` into a terminal).

## 1. Acceptance criteria

| # | Criterion | Requirement | Evidence |
|---|---|---|---|
| A1 | `weave next --format json` gives `why`, `see`, `decision`, `missing` and the evidence for every stage | R1, R2 | E1 |
| A2 | A skip is recorded with its reason and `missing`, shown in the Outline, read back before going live, and removable | R3, D19 | E1, E3 |
| A3 | `design-harness` appears for every workflow and can be skipped | R4, D13 | E1 |
| A4 | `guide-context` stays within its bound and holds no secret, prompt text or model output (canaries) | R5, D12 | E1 |
| A5 | Every fact has a command that exists, a link that resolves and a "does not do"; every moment names a fact that exists | R6, R7 | E1 (`make docs-check`) |
| A6 | Events carry identifiers only, are bounded and are marked discussed | R8, R9 | E1 |
| A7 | Notices follow the rules, the rate limit and the setting; the status bar count is right | R10, D9 | E1 |
| A8 | Each show tool opens the right thing; `show_file` stays in the root; `open_secret_form` returns no value; no run starts without the developer's confirmation | R12 | E1, E3 |
| A9 | Each host registers read, show and steering tools only; the Claude agent allows nothing else | principle 41, R17 | E1 (each guard broken once) |
| A10 | One MCP server serves both hosts; both agent files carry the same persona text and allow only the Guide's tools and reading | D8, D10 | E1 |
| A11 | The editor channel opens no connection, hands the editor only whole requests, refuses what is not listed or not inside the project, and fails clearly when the editor is not open or does not answer | DESIGN §5 | E1 |
| A12 | An instruction for the builder is sent only by the developer's click; a mismatched checkpoint is flagged | R13, R14 | E1, E3 |
| A13 | The scenario dataset passes against recorded answers, including the planted-instruction, key and overclaim families | principles 43 to 51, D18 | E2 |
| A14 | The Guide works end to end on Copilot | R16, R19 | E3 (M1) |
| A15 | The Guide works end to end on Claude Code | R17, R19 | E3 (M2) |
| A16 | The skills carry the shared section and call the checkpoint; the fast lane is no longer offered unasked | R20, R21 | E0, E1 (skills docs tests), E3 |
| A17 | A Java developer new to llm4j goes from an idea to an evaluated, capped, observed run with the Guide, and can explain why each stage was there | the phase's goal | E4 (M3) |

## 2. Quality gates

- After each sub-phase: `make test-core`. After any change to docs, skills or commands: `make docs-check`.
- At the end of the phase: `make test-fast` on `ai-agent4j-loom`, the extension's tests (`npm test` in `src/loom/vscode-loom`), `make format-check` where it applies, and `make test-oracle` once.
- The JaCoCo gates of `ai-agent4j-loom` hold for the new classes. New guard logic (the skip list, the summary's filtering, the server's tool list) is added to the `mutation` profile, and its score does not drop.
- `make test-full` is left to CI.

## 3. Manual checks

- **M1 (Copilot).** In a clean folder with only Copilot installed, start from an idea and follow the Guide to a deployed workflow:
  - a notice on a new tool with effects;
  - the graph and review shown;
  - a skip recorded and read back;
  - the eval report explained;
  - a key set through the form;
  - a capped run observed in Fabric.
- **M2 (Claude Code).** The same with only Claude Code installed. Also once with a Copilot Guide and a Claude Code builder.
- **M3 (usability).** A Java developer new to llm4j, not the owner, uses the Guide for one hour. Record:
  - where they were confused;
  - whether notices helped or annoyed them;
  - whether they can say why the golden dataset and the real evaluation exist;
  - what they now think the stack does.

  Every confusion becomes a scenario or a fix.

## 4. Documentation owed when built

- The extension: `README.md`, `REFERENCE.md`, `CHANGELOG.md` (with the new minimum VS Code version) and the walkthrough; `docs/VERSION_MATRIX.md`.
- `wiki/` (a Guide page, linked from `Home.md`), `llms.txt`, and the command reference for the new `weave` commands (`make regen`).
- The skills and their packages.
- `docs/RELEASE_NOTES_<version>.md`.
- `docs/API_COMPATIBILITY.md` for the new JSON fields of `weave next` and the new commands.
- The stack-level pages, once, at the end.

## 5. Exit checklist

- [x] Every decision taken (D1 to D19, 2026-11-17)
- [x] G1 to G8 read and noted in §0 (GitHub terms section J still to read before release)
- [ ] A1 to A16 met; A17 run, with its findings recorded
- [ ] The gates of §2 green; what did not run is stated
- [ ] The documentation of §4 updated in the same change

## 7. Results of the build (2026-11-17)

Built in one session: 37a (`5c3f3a58`), the MCP server (`8a410654`, `30e4cbe7`), the editor side and the scenarios (`736bbb32`), the skills and documentation (`c84ea2ee`). Nothing has been run in a real VS Code with Copilot or Claude Code yet (M1, M2), and no usability session has been held (M3).

### What met its criterion

- A1 to A9, A11 to A13 and A16 at E1 or E2: `GuideJourneyTest`, `GuideCatalogueTest`, `GuideContextTest`, `GuideMcpServerTest`, `guideCompanion.test.ts`, `SkillConventionsTest`. Two guards were broken on purpose and their tests failed: prompt text added to the summary (the canary test), and a `write_file` tool added to the server.
- A10: one MCP server serves both hosts, and both agent files carry the same persona text and allow only reading and the Guide's tools (checked by code; whether each host really withholds unlisted tools is M1 and M2).
- A13: the eleven scenarios pass against recorded answers, replayed twice with no model call. Recorded with `claude-sonnet-5-5` as the Guide and `gpt-5.5` as the judge, about 740,000 input and 40,000 output tokens over five recording rounds, about $3.

### What recording found, and what changed because of it

- **Answers written next to tool calls were lost.** The Guide put its answer beside a `record_learning` call and then wrote a last message that referred to it. The persona now says: every tool call first, then the whole answer as the last message.
- **A key pasted into the chat was not called exposed.** The persona now says to treat it as exposed and replace it.
- **Two rubrics asked for more than a short answer holds,** and the judge missed that "200,000 tokens, 30 calls" is the declared budget. `first-eval-report` and `cost-question` were narrowed to their essentials after the first recording; the other rubrics are as first written.
- **Two code checks were wrong,** not the Guide: "must not say *I won't*" caught "I won't bring it up again", and "ready to deploy" caught "you're not ready to deploy".
- **The fingerprint used `Map.of`'s order,** which changes between runs; it is now sorted.

### Where the build differs from the design

1. **The editor channel is a file mailbox, not a loopback socket** (DESIGN §5 rewritten): the extension promises never to open a connection (spec 10, R32).
2. **The Claude host is a project agent, not an installed plugin:** Set Up writes `.claude/agents/llm4j-guide.md` and the `.mcp.json` entry, so nothing is installed. The entry is `weave guide-mcp ${CLAUDE_PROJECT_DIR:-.}` (Claude Code expands variables in `.mcp.json`), with no path of the machine, so a team can commit it; it needs the `weave` launcher on the PATH, which Set Up offers to add. On Windows the launcher is `weave.cmd`; whether Claude Code starts a `.cmd` by its bare name is part of M2. (First built with the extension's absolute jar path; changed at the owner's request.)
3. **The key "form" is a terminal** running `weave secrets set <NAME> --allow-host <host>`, which asks for the value with no echo; the tool answers "opened", not "saved" or "cancelled". It needs a secret store (`--secrets` or `LOOM_SECRETS`); without one `weave` says how to name one. **Open** (a smoother path for a developer's laptop).
4. **The summary's findings come from `weave audit`,** not the whole review; `explain_finding` explains any rule through `weave review --explain`.
5. **Not built:** a node highlighted by `show_graph` and a rule focused by `show_review` (both open the view and name the node or rule); "no notice while the developer types to the Guide" (the editor does not say when its chat input has focus); `run_summary`; (R19's one-time offer was added afterwards at the owner's request: once, in a later session than the walkthrough's first, in a folder with a `.loom` script and an assistant to run on.)
6. **Tool names:** `new_events` (was `events_since_discussed`), `explain_finding` (was `review_finding`), and `project_summary` holds the journey.
7. **The server speaks the MCP handshake era** (`initialize`, versions 2025-06-18, 2025-03-26 and 2024-11-05) and answers `server/discover` the same way; whether Copilot and Claude Code accept it is M1 and M2.
8. **G5:** section J of GitHub's terms is still to be read before release.

### Gates

- `make docs-check`: passes.
- `make test-fast` on `ai-agent4j-loom`: passes (5,804 tests, all coverage checks met). The first run failed the gate on the Guide's own classes; `GuideEdgesTest` closed it, with no limit lowered.
- The extension's suite: all 846 tests pass when run without `engine.integration.test` (one walkthrough test needed its step index moved after the Guide's step), and that file passes alone (4 of 4, 9 s). Run together with the other files it hung twice; it is not fixed here and is left for CI to show.
- `make test-oracle`: passes.
- `make format-check` was not run (it covers the core and addons, which this phase did not touch). The other `slow` tests and `make test-full` are left to CI.
