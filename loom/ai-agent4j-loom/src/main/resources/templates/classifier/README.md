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

## Set up your keys (once; put them in the secret store, not in files)

Checking, graphing, auditing and `--mock` runs need no key. A real run needs your model's key (for `gemini-2.5-flash`, `GEMINI_API_KEY`).
Keep it in an encrypted store, outside this folder's version control:

```bash
weave secrets create --secrets ~/.loom/keys.store                  # asks you to choose a passphrase
weave secrets set GEMINI_API_KEY --secrets ~/.loom/keys.store      # asks you to type the key (it is not shown)
weave secrets list --secrets ~/.loom/keys.store                    # names only, never values
```

Then add `--secrets ~/.loom/keys.store` to `weave run` and `weave eval` (it asks for the passphrase; for unattended runs give
`--secrets-key-env <VARIABLE>` or `--secrets-key-file <file>` instead). Built-in models find their usual key name in the store first.
A tool that needs a key (a search tool, say) is written `api_key: secret.NAME` in the script and stored the same way. The script
never holds a key. If a key is ever pasted somewhere shared, rotate it.

To measure quality for real, with your keys in the store (above), then:

```bash
weave eval main.loom --max-tokens 100000      # asks before it spends; passed, failed and unjudged are counted apart
weave eval main.loom --report report.html     # a page to show someone
```

To compare two wordings of the prompt fairly, add `prompts/classifier/v2.md` and run
`weave eval main.loom --prompt classifier@v1` and `--prompt classifier@v2` with the same dataset.

## Make it yours

`weave guide recipes` has tested, copy-and-paste changes (a different model for the editor, ask a person before publishing, add an agent, add a tool, mask personal data), each with a sentence you can give your coding agent.

- Replace the tickets in `eval/golden/classifier.yaml` with real ones, and the labels in `main.loom` and `prompts/classifier.md`.
- A judge model grades the `rubric` lines; use `--judge <model>` to name a different one from the model being judged.
