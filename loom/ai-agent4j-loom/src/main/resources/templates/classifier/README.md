# {{name}}

**This is a reference, not a finished product.** It shows the shape of a working project so you can see how the pieces fit. Change the agents, prompts, tools, golden dataset and limits to fit your own workflow before you rely on it.

Sorts a support ticket into `BUG`, `BILLING` or `FEATURE`, with a confidence. When the confidence is `LOW`, a person chooses
the label. The golden dataset in `{{golden}}` is the heart of this one: it is how you find out whether the labels are good, and
whether they stay good after you change the prompt. There is no Java to write here: the workflow is the script, and the Java tests only run the dataset.

The workflow is `{{script}}`, its prompts are in `{{prompts}}/`, and the golden dataset (the eval tests: example requests and what a good answer does) is in `{{golden}}/`.
In a Maven project `mvn test` runs every scenario of the dataset as its own JUnit test, on a model that costs nothing and writes the eval4j dashboard to `target/eval4j/report/index.html` (open it in a browser: it reports the wiring as passed and the quality dimensions as not yet evaluated, which a real, capped run changes).

Evaluation: golden dataset in `{{golden}}`

## Try it, in this order (nothing costs money until the last step)

```bash
weave check {{script}} --no-env
weave eval {{script}} --check          # the dataset is valid
weave eval {{script}} --mock           # the wiring runs, on a model that costs nothing (content checks show as unjudged)
```

## Set up your key (on your machine)

Checking, graphing, auditing and `--mock` runs need no key. A real run needs your model's key (for `gemini-2.5-flash`, `GEMINI_API_KEY`).

```bash
cp .env.example .env        # then open .env and put your key after GEMINI_API_KEY=
```

`.env` is ignored by git (`weave init` added it to `.gitignore`), so it is never committed. `weave run` and `weave eval` read it from this folder and say which
names they found (never the values). A variable already set in your shell wins over the file. If you judge with another provider's model, add that key there too.
If git tracks `.env`, `weave` refuses to use it.

## Deploying this to a server?

Do not use `.env` there. Put the keys in the secret store (`weave secrets create`, `weave secrets set`, then `--secrets <file>` on `weave run`) or in a vault your host
code reads (a Java `SecretStore`): `weave guide 9` explains both.

To measure quality for real, with your key in `.env` (above), then:

```bash
weave eval {{script}} --max-tokens 100000      # asks before it spends; passed, failed and unjudged are counted apart
weave eval {{script}} --report report.html     # a page to show someone
```

To compare two wordings of the prompt fairly, add `{{prompts}}/classifier/v2.md` and run
`weave eval {{script}} --prompt classifier@v1` and `--prompt classifier@v2` with the same dataset.

## Make it yours

`weave guide recipes` has tested, copy-and-paste changes (a different model for the editor, ask a person before publishing, add an agent, add a tool, mask personal data), each with a sentence you can give your coding agent.

- Replace the tickets in `{{golden}}/classifier.yaml` with real ones, and the labels in `{{script}}` and `{{prompts}}/classifier.md`.
- A judge model grades the `rubric` lines; use `--judge <model>` to name a different one from the model being judged.
