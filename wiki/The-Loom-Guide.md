# The Loom Guide

The Loom Guide is a companion that teaches you while your workflow is built. Your coding agent (the builder) writes the code; the Guide explains each stage before it happens, opens the graph, the review and the eval report at the right moment, and asks you for the decisions that are yours: a threshold, a budget, an accepted risk, going live. It never edits a file, so it is free to answer your questions while the builder works.

Its goal is the same as the skills': a workflow that is tested, evaluated, cost-effective, durable and ready for production, built by someone who understands it. Speed is not the goal. If you want to skip a stage, it says once what you will miss, records the skip, and carries on.

## Set it up

The Guide runs as an `llm4j-guide` agent of the assistant you already use, on your own Copilot or Claude subscription: no new key, no new bill. In VS Code (1.101 or newer):

1. Run **Loom: Set Up the Guide** (the extension also offers it once, in a later session than your first walkthrough). It writes `.github/agents/llm4j-guide.agent.md` for Copilot, `.claude/agents/llm4j-guide.md` for Claude Code, and the `llm4j-guide` server in `.mcp.json` as `weave guide-mcp ${CLAUDE_PROJECT_DIR:-.}`: no path of your machine, so all three files can be committed for the team. Claude Code starts it with the `weave` launcher; Set Up offers to put it on your PATH if it is not there. A file you changed is left alone.
2. Run **Loom: Open the Guide**, or click the Guide's note count in the status bar:
   - **Copilot:** choose `llm4j-guide` in Copilot Chat's agent list.
   - **Claude Code:** a terminal opens running `claude --agent llm4j-guide`.

Both agents carry the same instructions (`src/loom/vscode-loom/guide/persona.md`) and may use only reading and the Guide's tools. When the extension updates, it brings these files up to the new instructions at start-up, but only the ones it wrote and you have not changed.

## What it knows, and where it comes from

The Guide never relies on its memory of the chat to know where you are. It reads the project:

- **The journey**, from `weave next --format json`: eleven stages, from deciding the agents and designing the harness to a watched deployment. Each stage says why it exists, what to look at when it is done, the decision that is yours, what a skip loses, and the evidence its state was read from.
- **A project summary**, from `weave guide-context`: the agents and tools with their effects, the audit's findings, the declared budget, your skips, and the *feature moments* that apply (for example, a tool that changes something with no approval in front of it brings up approvals and the journal).
- **The facts it may state**, from `weave guide-facts`: what each feature is for, the problem it solves, the command that shows it, and what it does **not** do. The Guide words these for your project but never claims more than they say.
- **What happened since you last talked**: stage changes and saves the editor records, and the builder's checkpoints. A builder's claim the project does not support (for example "the evaluation is done" with no real evaluation recorded) is flagged.

```bash
weave next --skip golden-dataset --because "a demo for Friday"   # a skip, shown with what it loses
weave next --unskip golden-dataset
weave guide-context                                               # what the Guide reads at the start of a turn
weave guide-facts approvals
```

## What it can do in the editor

Through `weave guide-mcp`, its MCP server, the Guide can open the graph, the Review panel, the eval4j report, a file of the project, the live run page, and the walkthrough. It can ask you to start one capped, observed run (you confirm the cap), open a terminal where you type a key with no echo (`weave secrets set NAME --allow-host HOST`), and show an instruction for your coding agent that you put in the agent's chat yourself. It never asks for a key in the chat.

There is no network between the Guide and the editor: requests and answers are files in `.loom/guide/`, which is git-ignored.

## Notes

When a stage starts failing or goes out of date, your first real result arrives, or the builder claims a stage the project does not show, a notice offers **Ask the Guide**. At most one notice appears every two minutes; the setting `loom.guide.notices` turns them off. The status bar counts the notes the Guide has not discussed.

## How it is tested

The Guide's behaviour is a golden dataset of its own: eleven scenarios (you want to skip the dataset, the builder claims a stage, a review finding, your first evaluation, a key pasted in the chat, the first paid run, a feature that does not exist, a cost question, a newcomer to agents, instructions planted in a README, a plain question). Each is checked by code and then judged by eval4j with a different model. The default build replays recorded answers; recording them again is a live run. See `GuideScenariosTest` and [the phase 37 spec](../docs/specs/37-the-guide/DESIGN.md).

## Related

- [Working with a coding agent](Working-with-a-Coding-Agent.md): setting up the builder.
- [Loom in VS Code](Loom-in-VS-Code.md): the graph, the review and the debugger the Guide opens.
- [Your first run](Your-First-Run.md): `weave try` and `weave next`.
