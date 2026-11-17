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
| G1 | The VS Code chat participant and language model tool APIs: the minimum version to raise `engines.vscode` to, how a participant gets a model, the behaviour with no model | D11, R16 | 37c, first |
| G2 | Whether a chat participant, or Copilot's agent mode, can use an MCP server registered by an extension | D10 option B | 37c |
| G3 | The Claude Code plugin format: an agent with a tool allow-list, MCP server configuration, how a plugin is installed from a VS Code extension | R17 | 37d, first |
| G4 | Whether the Claude Code VS Code extension can open a new session with a named agent, from a command or a link | D8, R17 | 37d, first |
| G5 | **The terms of GitHub Copilot and of Claude subscriptions for an extension that uses them through their own extensions,** and anything they forbid (for example automated use, or use outside the editor) | D7; a Guide that broke the terms would be withdrawn | before 37c |
| G6 | How the engine (`weave serve`) is reached from the extension today, and whether `weave guide-mcp` can reuse its protocol for the Java parts | D10 | 37b |
| G7 | How the review classes a workflow as autonomous (a loop, schedule, `supervise`, daemon, effects with no approval), so that the `design-harness` stage's text can say whether this workflow needs a short or a full harness design | R4, D13 | 37a |
| G8 | Where the eval4j report is written and how the extension opens it today | R12 | 37b |

### Notes from the reads (written by the build; each says when it was read)

None yet.

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
| A10 | Both bindings match the one tool contract | D10 | E1 |
| A11 | The bridge refuses without the token, listens on loopback only, and fails clearly when the extension is not running | DESIGN §5 | E1 |
| A12 | An instruction for the builder is sent only by the developer's click; a mismatched checkpoint is flagged | R13, R14 | E1, E3 |
| A13 | The scenario dataset passes against recorded answers, including the planted-instruction, key and overclaim families | principles 43 to 51, D18 | E2 |
| A14 | The Guide works end to end on Copilot | R16, R19 | E3 (M1) |
| A15 | The Guide works end to end on Claude Code | R17, R19 | E3 (M2) |
| A16 | The skills carry the shared section and call the checkpoint; the fast lane is no longer offered unasked | R20, R21 | E0, E1 (skills docs tests), E3 |
| A17 | A Java developer new to llm4j goes from an idea to an evaluated, capped, observed run with the Guide, and can explain why each stage was there | the phase's goal | E4 (M3) |

## 2. Quality gates

- After each sub-phase: `make test-core`. After any change to docs, skills or commands: `make docs-check`.
- At the end of the phase: `make test-fast` on `ai-agent4j-loom`, the extension's tests (`npm test` in `src/loom/vscode-loom`), `make format-check` where it applies, and `make test-oracle` once.
- The JaCoCo gates of `ai-agent4j-loom` hold for the new classes. New guard logic (the skip list, the summary's filtering, the bridge's token check) is added to the `mutation` profile, and its score does not drop.
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
- [ ] G1 to G8 read and noted in §0
- [ ] A1 to A16 met; A17 run, with its findings recorded
- [ ] The gates of §2 green; what did not run is stated
- [ ] The documentation of §4 updated in the same change
