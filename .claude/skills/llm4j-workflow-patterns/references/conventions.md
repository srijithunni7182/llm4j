# Conventions every llm4j skill follows

These nine rules are the same in the harness-design, workflow-guide and loom-deployer skills (and in the evaluation-design skill when it ships). If you read one skill's copy, you have read them all. A test keeps the copies identical.

## 1. Show the roadmap first, the checklist after every stage

Begin by showing where the project stands: run `weave next` (it is free) and show its steps, or the skill's own list when there is no project yet. End every stage with the same checklist, so the person always sees what is done, what is next and what was skipped:

```
Stage 3 of 9: Golden dataset
[x] Stages 1-2 done
[x] 3  Golden dataset (12 cases, 4 reviewed by you)
[ ] 4  Prompt tests          <- next
[-] 5  Prompt optimisation   skipped: "not needed yet" (your words)
```

`[x]` done, `[ ]` not yet, `[-]` skipped with the person's reason.

## 2. Ask whether a coordinating step needs a model

For every planner, router, supervisor or merger in the person's description, ask first: **is this a model call, or can the script decide?** Record the answer in the design before designing anything else.

## 3. Write datasets with the person, and show who wrote what

Ask the questions of the dataset chapter one agent at a time and write cases only from the answers. Never invent a scenario the person did not choose; ask for the unanswerable, the obviously-wrong-answer and the cautious-action cases. If the person asks you to draft cases, tag each `author:assistant`. When they accept or edit one, they add `reviewed_by:<their name>`. `weave eval --check` reports how many drafted cases no person has reviewed.

## 4. Read one real answer early

After the preflight (`weave preflight`), run one capped real run and show the person the output. Do not build the dataset until they say what is right and wrong with it. Then record it: `weave next --confirm real-run`.

## 5. Read back what you wrote, and describe only what you have read

After you write or change a script, run `weave explain` and read the result back to the person before any tests are written. For a generated project, run `weave generate --gate-only`. Never describe a report, dashboard or graph you have not opened and read in this session. If you cannot open it, say so.

## 6. Run the README's commands once

Before you call a project ready, run each command in its README in a form that does not spend (`--check`, `--mock`, `--dry-run`, or the real command with a cap). Mark each command `tested` or `untested` in the README. Never leave an untried command in silently.

## 7. Keys: ask first, offer both routes

The first question about keys is: **will you run this yourself, or deploy it?** Then offer both routes side by side: the secret store (`weave secrets`), and a `.env` file that is git-ignored. Say it plainly: never put a key in `.env.example`, in a script or in a committed file. Never ask for a key in the conversation.

## 8. Say once that selecting text shares it

Selecting text in the editor sends it to the conversation. Name the files not to select: `.env`, `*.key`, the secret store's master key and the Fabric admin password file. Offer to add deny rules for them to the coding agent's settings (for Claude Code, `permissions.deny` entries in `.claude/settings.json`). Show the lines first and write them only after a yes. Deny rules reduce accidents; they do not make a secret that is already shared private again.

## 9. Skipping is visible

When the person skips a step (the preflight, the evaluation, a review), record the step and their reason in the design record and the checklist. A skipped step is shown as skipped from then on; it is never dropped.
