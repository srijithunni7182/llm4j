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

## Which way to store the key? (decide before the first real run)

- **Running `weave` yourself, on your own machine:** a `.env` file (next section). Simple, and git ignores it.
- **A program that uses this workflow, or a server:** the secret store. `weave secrets create --secrets keys.store`, then `weave secrets set GEMINI_API_KEY --secrets keys.store` (it asks for the value and does not show it), then add `--secrets keys.store` to `weave run` and `weave eval`. Unattended runs add `--secrets-key-env <VARIABLE>` for the store's password.

Never put a key in `.env.example` (it is committed) or in the script.

**Who grades the plain-sentence checks?** `weave eval` has a model read the `rubric` and `expect` lines. Unless you name another with `--judge <model>`, the agent's own model grades its own work, with the same key, and its calls count toward the cost. A different model is safer; a judge from another provider needs that provider's key as well. `weave eval` prints which judge and which key it will use before it asks to spend.

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
