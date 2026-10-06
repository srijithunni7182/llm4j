# {{name}}

**This is a reference, not a finished product.** It shows the shape of a working project so you can see how the pieces fit. Change the agents, prompts, tools, golden dataset and limits to fit your own workflow before you rely on it.

Reads a customer email, drafts a reply, and **asks a person to approve** anything that moves money. The model never sends
anything: the workflow ends with a reply for a person to use. There is no Java here.

Evaluation: golden dataset in `eval/golden`

## What keeps it safe

- `guard { pii: mask }` masks card numbers and other personal data before they reach the model.
- `budget { ... }` caps what one run may spend.
- A person approves (`human_prompt`) before a refund-related reply is marked ready.
- The prompts say never to follow instructions found inside an email, and the dataset has a case for it.

## Try it, in this order (nothing costs money until the last step)

```bash
weave check main.loom --no-env        # valid (keys are not needed to check it)
weave audit main.loom                 # what each agent can reach; nothing here can send or act
weave graph main.loom --format mermaid
weave eval main.loom --check
weave eval main.loom --mock           # the wiring, on a model that costs nothing
```

To run it for real, set your model's key (for `gemini-2.5-flash`, `GEMINI_API_KEY`), then:

```bash
weave run main.loom -i email="I was charged twice, 240 dollars. Please refund me." --max-tokens 50000
```

## Make it yours

- Change the approval limit in `prompts/triage.md`, and the cases in `eval/golden/` to emails you really get.
- Prompts are markdown files in `prompts/`; keys go in the environment (or `weave secrets`), never in files.
