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

To run it for real, with your keys in the store (above), then:

```bash
weave run main.loom -i email="I was charged twice, 240 dollars. Please refund me." --max-tokens 50000
```

## Make it yours

`weave guide recipes` has tested, copy-and-paste changes (a different model for the editor, ask a person before publishing, add an agent, add a tool, mask personal data), each with a sentence you can give your coding agent.

- Change the approval limit in `prompts/triage.md`, and the cases in `eval/golden/` to emails you really get.
- Prompts are markdown files in `prompts/`; keys go in the secret store (`weave secrets`), never in files.
