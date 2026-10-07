# {{name}}

**This is a reference, not a finished product.** It shows the shape of a working project so you can see how the pieces fit. Change the agents, prompts, tools, golden dataset and limits to fit your own workflow before you rely on it.

A small pipeline: a researcher gathers notes, a writer drafts from them, and an editor sends weak drafts back (at most twice).
Everything is plain files; the Java is only the tests that run the golden dataset.

The workflow is `{{script}}`, its prompts are in `{{prompts}}/`, and the golden dataset (the eval tests: example requests and what a good answer does) is in `{{golden}}/`.
In a Maven project `mvn test` runs every scenario of the dataset as its own JUnit test, on a model that costs nothing.

Evaluation: golden dataset in `{{golden}}`

## Try it, in this order (nothing here costs money until the last step)

```bash
weave check {{script}} --no-env        # the script is valid (keys are not needed to check it)
weave graph {{script}} --format mermaid  # see the workflow
weave audit {{script}}                 # what each agent can reach
weave eval {{script}} --check          # the golden dataset is valid
weave eval {{script}} --mock           # the wiring runs end to end, on a model that costs nothing
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

To run it for real, with your key in `.env` (above), change the `model:`
lines if you use another provider, then:

```bash
weave run {{script}} -i topic="home composting" --max-tokens 100000
weave eval {{script}} --max-tokens 200000     # asks before it spends anything
```

## Make it yours

`weave guide recipes` has tested, copy-and-paste changes (a different model for the editor, ask a person before publishing, add an agent, add a tool, mask personal data), each with a sentence you can give your coding agent.

- Each agent's prompt is a markdown file in `{{prompts}}/`. Edit it, or add `{{prompts}}/researcher/v2.md` and compare with
  `weave run {{script}} ... --prompt researcher@v1`.
- Replace the cases in `{{golden}}/` with requests you really expect, and what a good answer does.
- Keys stay in `.env` (ignored by git), never in the script or the prompts.
