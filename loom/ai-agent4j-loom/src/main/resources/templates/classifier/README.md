# {{name}}

**This is a reference, not a finished product.** It shows the shape of a working project so you can see how the pieces fit. Change the agents, prompts, tools, golden dataset and limits to fit your own workflow before you rely on it.

Sorts a support ticket into `BUG`, `BILLING` or `FEATURE`, with a confidence. When the confidence is `LOW`, a person chooses
the label. The golden dataset in `eval/golden` is the heart of this one: it is how you find out whether the labels are good, and
whether they stay good after you change the prompt. There is no Java here.

Evaluation: golden dataset in `eval/golden`

## Try it, in this order (nothing costs money until the last step)

```bash
weave check main.loom --no-env
weave eval main.loom --check          # the dataset is valid
weave eval main.loom --mock           # the wiring runs, on a model that costs nothing (content checks show as unjudged)
```

To measure quality for real, set your model's key (for `gemini-2.5-flash`, `GEMINI_API_KEY`), then:

```bash
weave eval main.loom --max-tokens 100000      # asks before it spends; passed, failed and unjudged are counted apart
weave eval main.loom --report report.html     # a page to show someone
```

To compare two wordings of the prompt fairly, add `prompts/classifier/v2.md` and run
`weave eval main.loom --prompt classifier@v1` and `--prompt classifier@v2` with the same dataset.

## Make it yours

- Replace the tickets in `eval/golden/classifier.yaml` with real ones, and the labels in `main.loom` and `prompts/classifier.md`.
- A judge model grades the `rubric` lines; use `--judge <model>` to name a different one from the model being judged.
