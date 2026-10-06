# {{name}}

**This is a reference, not a finished product.** It shows the shape of a working project so you can see how the pieces fit. Change the agents, prompts, tools, golden dataset and limits to fit your own workflow before you rely on it.

A small pipeline: a researcher gathers notes, a writer drafts from them, and an editor sends weak drafts back (at most twice).
Everything is plain files; there is no Java here.

Evaluation: golden dataset in `eval/golden`

## Try it, in this order (nothing here costs money until the last step)

```bash
weave check main.loom --no-env        # the script is valid (keys are not needed to check it)
weave graph main.loom --format mermaid  # see the workflow
weave audit main.loom                 # what each agent can reach
weave eval main.loom --check          # the golden dataset is valid
weave eval main.loom --mock           # the wiring runs end to end, on a model that costs nothing
```

To run it for real, set your model's key (for `gemini-2.5-flash`, `GEMINI_API_KEY`) in your environment, change the `model:`
lines if you use another provider, then:

```bash
weave run main.loom -i topic="home composting" --max-tokens 100000
weave eval main.loom --max-tokens 200000     # asks before it spends anything
```

## Make it yours

- Each agent's prompt is a markdown file in `prompts/`. Edit it, or add `prompts/researcher/v2.md` and compare with
  `weave run main.loom ... --prompt researcher@v1`.
- Replace the cases in `eval/golden/` with requests you really expect, and what a good answer does.
- Keys never go in files: use the environment, or `weave secrets`.
