---
name: llm4j-workflow-patterns
description: Picks the shape of an llm4j workflow from what the user wants to build, and gives a tested starting skeleton for it: route by label, write-review loop, parallel research, plan and fan out, approval before anything is sent, extract fields from text, answers from documents, wait for a person, run on a schedule, long step-by-step job, check against house rules. Use at the very start, when the user describes a goal and has not chosen agents or structure, or asks how to structure or lay out a workflow. Building and testing it is llm4j-workflow-guide.
---

# llm4j workflow patterns

Most workflows are one of a handful of shapes. Finding the shape first saves the blank page: the person decides a few things, you start from a
skeleton that already loads and has tests, and the rest of the work is the usual path (`llm4j-workflow-guide`).

**The repository may not be here: never send the user to a repository path and never ask them to fetch one.** What you need is in
`references/patterns.md` (the shapes, each with a tested Loom skeleton, the questions to ask and the traps) and in the tools: `weave guide loom`
is the language reference, `weave <command> --help` the truth about options. Follow `references/conventions.md`: the roadmap and checklist,
asking whether a coordinating step needs a model, keys, and making a skipped step visible.

## How to use this skill

1. **Ask what the workflow is for, in the person's words.** One sentence: what goes in, what comes out, what must never happen. Do not
   start with agents.
2. **Pick the pattern, say why, and say what you did not pick.** Match the sentence to `references/patterns.md`. Read the matching
   pattern's "Use it when" aloud. If two fit, say so and let the person choose. If none fits, say that too, and use `weave generate
   "<what it should do>" --plan-only --out plan` from the workflow guide.
3. **Ask the questions under that pattern, one at a time**, and write the answers down. They are the design. A skeleton with the questions
   unanswered is a guess.
4. **Combine on purpose.** Patterns meet: a route in front, an approval at the edge, a schedule around it. Keep the control flow in the
   script and give each agent one job. For every step that only chooses a label, ask whether it needs a model at all.
5. **Start from the skeleton, not from nothing.** For a new project, `weave init pipeline|approval|classifier|empty` (`weave init --list`) is the
   closest of the four ready-made projects; otherwise copy the skeleton from `references/patterns.md`, change the `model:` lines to the
   person's provider, and change the names. Then `weave check` and `weave explain`, and read the explanation back (convention 5).
6. **Hand over to `llm4j-workflow-guide`** at the stage the pattern names: the golden dataset first by default. Say which stages you
   skipped, with the person's reason.

## Show the choice, not only the answer

Draw it before building: `weave graph workflow.loom --format mermaid` shows the person the shape. If it is not the workflow they
meant, change it now; it is the cheapest moment.

## Things to say out loud

- A pattern is a starting point. The skeleton's prompts are placeholders; the golden dataset and the person's own rules make it theirs.
- The skeleton uses `gemini-2.5-flash` everywhere. Models are the person's choice (`llm4j-workflow-guide`, chapter 16 for cost).
- A pattern that sends, pays, publishes or deletes ends with a person's approval at the edge. Do not remove it to make a demo shorter.
- Nothing here has been run with a real model. The skeletons are checked and tested with scripted models; how good the answers are
  is what `weave eval` measures.

## Related skills

- `llm4j-workflow-guide`: build, test, secure and ship the workflow once the shape is chosen.
- `llm4j-harness-design`: for an autonomous workflow, the bounds, durability and who may step in, decided before it is built.
- `llm4j-eval-design`: how the workflow will be judged.
- `llm4j-run-debugging`: when a run is wrong, slow, expensive or stuck.
- `llm4j-loom-deployer`: running it where people can watch.
