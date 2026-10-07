# {{name}}

**This is a reference, not a finished product.** It shows the shape of a working project so you can see how the pieces fit. Change the agents, prompts, tools, golden dataset and limits to fit your own workflow before you rely on it.

Reads a customer email, drafts a reply, and **asks a person to approve** anything that moves money. The model never sends
anything: the workflow ends with a reply for a person to use. There is no Java to write here: the workflow is the script, and the Java tests only run the dataset.

The workflow is `{{script}}`, its prompts are in `{{prompts}}/`, and the golden dataset (the eval tests: example requests and what a good answer does) is in `{{golden}}/`.
In a Maven project `mvn test` runs every scenario of the dataset as its own JUnit test, on a model that costs nothing and writes the eval4j dashboard to `target/eval4j/report/index.html` (open it in a browser: it reports the wiring as passed and the quality dimensions as not yet evaluated, which a real, capped run changes).

Evaluation: golden dataset in `{{golden}}`

## What keeps it safe

- `guard { pii: mask }` masks card numbers and other personal data before they reach the model.
- `budget { ... }` caps what one run may spend.
- The workflow, not the model, decides who must approve: the model only reads the kind of email and the refund amount, and the script asks a person for any refund over 100 dollars, or whose amount could not be read. (The model still reads the amount, so for real money keep the person in the loop for what you pay out.)
- The prompts say never to follow instructions found inside an email, and the dataset has a case for it.

## Try it, in this order (nothing costs money until the last step)

```bash
weave check {{script}} --no-env        # valid (keys are not needed to check it)
weave audit {{script}}                 # what each agent can reach; nothing here can send or act
weave graph {{script}} --format mermaid
weave eval {{script}} --check
weave eval {{script}} --mock           # the wiring, on a model that costs nothing
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

To run it for real, with your key in `.env` (above), then:

```bash
weave run {{script}} -i email="I was charged twice, 240 dollars. Please refund me." --max-tokens 50000
```

## Make it yours

`weave guide recipes` has tested, copy-and-paste changes (a different model for the editor, ask a person before publishing, add an agent, add a tool, mask personal data), each with a sentence you can give your coding agent.

- Change the approval limit in `{{script}}` (the `alt (triage.amount > 100)` line), and the cases in `{{golden}}/` to emails you really get.
- Prompts are markdown files in `{{prompts}}/`; keys go in `.env` (ignored by git) on your machine and in the secret store on a server, never in the script.
