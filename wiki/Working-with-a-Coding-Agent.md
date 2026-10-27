# Working with a coding agent in VS Code

Loom is built so that you can describe a workflow and let an agent do most of the typing, while `weave` keeps it honest. This page is how to set that up and what to expect.

## 1. Teach the agent (one click)

In VS Code run **Loom: Set Up My Coding Agent** (or click *Set up my coding agent* in the Loom view), or from a terminal in your project:

```
weave guide --install-skill . --for claude,agents
```

| You use | It writes | Notes |
|---|---|---|
| Claude Code | `.claude/skills/llm4j-*` (three skills) and the guide | the long version: build, test, secure, harness design, run and watch |
| Codex, Copilot's agent mode, Gemini, Cursor, others | a block in `AGENTS.md` | the short version below |
| GitHub Copilot chat | `.github/copilot-instructions.md` | same text |
| Cursor | `.cursor/rules/loom.mdc` | applies to `.loom`, `.test.yaml` and `tools.loot` files |

Your own text in those files stays: Loom's part sits between `<!-- loom:begin -->` and `<!-- loom:end -->`. Run the command again with `--force` to refresh only that block after you update `weave`.

## 2. What the agent is told

Read it with `weave guide agents`. In short:

- **Look commands up, do not guess.** `weave man` lists every command by what you are doing, `weave man <command>` prints a page with every option, its default, examples and exit codes. The same text is in [docs/COMMAND_REFERENCE.md](https://github.com/srijithunni7182/llm4j/blob/main/docs/COMMAND_REFERENCE.md).
- **The loop.** Describe or change with `weave generate` (plan first, you read it), check and review, show the graph and the plain-English explanation, test, evaluate, watch a real run, close the loop from production with `capture` and `regress`, then `bindgen` and `deploy`.
- **The rules.** No keys in files. Ask before anything that spends money or has an effect. Never weaken a test to pass a gate. Tell you what the tool did not check.

## 3. A good session

1. Ask the agent: *"Build a workflow that extracts vendor, date and total from receipts and sends anything over 500 to me for approval."*
2. It runs `weave generate ... --plan-only` and reads you `PLAN.md`. Correct it here; this is the cheapest place.
3. It builds, then shows `weave explain` and the graph. Say whether that is what you meant.
4. It runs `weave test` (free, instant) and `weave review`, and tells you what is open.
5. You decide when to spend: `weave eval` against a real model, a real run with `--observe`, `weave deploy`.

The commands that cost model tokens are `weave generate` (a few cents with a cheap model), and `weave eval` / `weave run` against real models. Everything else is free, so an agent can run them as often as it likes.

## What to check by hand

- The agent actually reads the file (ask it: *"what does this project say about keys?"*).
- It uses `weave man` before it writes a flag you have not seen.
- After `weave guide --install-skill --for agents` twice, the second run says the instructions are already there and changes nothing.
