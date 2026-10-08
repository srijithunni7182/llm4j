# {{name}}

An empty starting point: one agent (`Helper`), one workflow (`Main`) and one example. There is nothing here to delete before you make it yours.

The workflow is `{{script}}`, its prompts are in `{{prompts}}/`, and the golden dataset (the eval tests: example requests and what a good answer does) is in `{{golden}}/`.
In a Maven project `mvn test` runs every scenario of the dataset as its own JUnit test, on a model that costs nothing, and writes the eval4j dashboard to `target/eval4j/report/index.html`.

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

`.env` is ignored by git. `weave run` and `weave eval` read it and say which names they found (never the values).

## Deploying this to a server?

Do not use `.env` there: use the secret store above, or a vault your Java host reads (`weave guide 9`).

## Make it yours

Rename `Helper`, write its job in `{{prompts}}/helper.md`, add agents, and replace the example with real ones. `weave guide recipes` has tested changes to copy.
