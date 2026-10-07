# Recipes: change the starter to do what you want

Every recipe below is a small, tested change to the **`pipeline`** starter (`weave init pipeline my-workflow`). Each says what you get, shows the exact lines to find and what to
put in their place, and gives the sentence to give your coding agent if you would rather ask than edit. The file names below are the script and prompts of the starter; in the Maven project `weave init` makes, they sit under `src/main/resources/` (`src/main/resources/main.loom`, `src/main/resources/prompts/writer.md`). After any change run `weave check <the script> --no-env`; it is
free and tells you if the change broke something. (A test applies every recipe to a fresh starter and runs the check, so the lines here are the real ones.)

Recipes that need Java code (a new tool, a step that must always run), splitting a long script, keys, and putting a screen on a workflow are in chapters 6 and 9
(`weave guide 6`, `weave guide 9`).

## Give the editor a different model

A reviewer that is the same model as the writer tends to agree with it. A different, cheaper model is a better second opinion.

> Ask your agent: *"Make the Editor use a different model than the Writer, and say what it will cost."*

Find:

```loom recipe=different-model find
agent Editor     { model: "gemini-2.5-flash"  prompt: "editor"      temperature: 0.1 }
```

Replace with:

```loom recipe=different-model replace
agent Editor     { model: "claude-haiku-4-5-20251001"  prompt: "editor"      temperature: 0.1 }
```

You now need that provider's key too: add `ANTHROPIC_API_KEY=` to your `.env` (the starter's `.env.example` shows where).

## Ask a person before anything is published

The workflow ends with a note. Put a question to a person first, and only publish on a yes.

> Ask your agent: *"Before the issue is marked ready, ask a person to approve it, and say so in the note if they decline."*

Find:

```loom recipe=approve-before-publishing find
    note "Issue ready:\n{draft_text}"
```

Replace with:

```loom recipe=approve-before-publishing replace
    human_prompt "Publish this issue? (yes/no)\n\n{draft_text}" -> publish_answer
    alt (publish_answer == "yes") {
        note "Issue ready:\n{draft_text}"
    } else {
        note "Not published: a person declined."
    }
```

Both answers lead somewhere different, so `weave check` is happy; if they led to the same steps it would tell you the question changes nothing.

## Ask a person when the review loop gives up

The editor can send a draft back twice. If it is still not good, today the workflow carries on regardless. Ask a person instead.

> Ask your agent: *"If the editor still rejects the draft after the last round, ask a person what to do instead of carrying on."*

Find:

```loom recipe=escalate-when-the-loop-gives-up find
            delegate "Rewrite the draft. The editor says: {review.advice}\n\n{draft_text}" to Writer -> draft_text
        }
    }
```

Replace with:

```loom recipe=escalate-when-the-loop-gives-up replace
            delegate "Rewrite the draft. The editor says: {review.advice}\n\n{draft_text}" to Writer -> draft_text
        }
    } on_exhausted {
        human_prompt "The editor still is not satisfied after two rewrites. Publish anyway? (yes/no)\n\n{draft_text}" -> override_answer
        note "A person said {override_answer} to publishing a draft the editor did not pass."
    }
```

## Add a step: a fact-checker between the researcher and the writer

A new agent, its prompt as a file, and one new step. The writer now works from the checked notes.

> Ask your agent: *"Add a FactChecker agent after the Researcher that removes any claim the notes do not support, and have the Writer use its output."*

Find the agents, and add one:

```loom recipe=add-agent find
agent Researcher { model: "gemini-2.5-flash"  prompt: "researcher"  temperature: 0.3 }
```

```loom recipe=add-agent replace
agent Researcher { model: "gemini-2.5-flash"  prompt: "researcher"  temperature: 0.3 }
agent FactChecker { model: "gemini-2.5-flash"  prompt: "fact_checker"  temperature: 0.0 }
```

Find the step that writes, and put the check before it:

```loom recipe=add-agent find
    delegate "Write this week's issue about {topic} from these notes:\n{research_notes}" to Writer -> draft_text
```

```loom recipe=add-agent replace
    delegate "Check these notes. Remove every claim that is not supported by a source in them, and list what you removed:\n{research_notes}" to FactChecker -> checked_notes
    delegate "Write this week's issue about {topic} from these notes:\n{checked_notes}" to Writer -> draft_text
```

And the new prompt file, `prompts/fact_checker.md`:

```markdown recipe=add-agent file=prompts/fact_checker.md
You check research notes before they are used.

- Keep a claim only if the notes give a source for it.
- Remove everything else, and list what you removed under "Removed:".
- Never add facts of your own.
```

Add a case for the new agent to `eval/golden/` when you want it evaluated (`weave eval main.loom --init` writes a starter for it).

## Give the researcher web search

The built-in `web_search` tool needs no setup. Name it in the agent's `tools`.

> Ask your agent: *"Let the Researcher search the web, and make sure it says which sources it used."*

Find:

```loom recipe=web-search find
agent Researcher { model: "gemini-2.5-flash"  prompt: "researcher"  temperature: 0.3 }
```

Replace with:

```loom recipe=web-search replace
agent Researcher { model: "gemini-2.5-flash"  prompt: "researcher"  temperature: 0.3  tools: [web_search] }
```

A tool that acts on the world (sends, writes, pays) should also be listed under the agent's `approve: [...]`, so a person is asked first.

## Keep personal data away from the model

`guard { pii: mask }` masks card numbers, e-mail addresses and similar before the text reaches the model.

> Ask your agent: *"Mask personal data before it reaches the Writer."*

Find:

```loom recipe=mask-personal-data find
agent Writer     { model: "gemini-2.5-flash"  prompt: "writer"      temperature: 0.7 }
```

Replace with:

```loom recipe=mask-personal-data replace
agent Writer {
    model: "gemini-2.5-flash"
    prompt: "writer"
    temperature: 0.7
    guard { pii: mask }
}
```

## Change how the editor behaves

This one is not a change to the script at all. Each agent's prompt is a markdown file in `prompts/`: edit `prompts/editor.md`. To compare two wordings fairly, keep both as versions
(`prompts/editor/v1.md`, `prompts/editor/v2.md`) and run each: `weave run main.loom -i topic="..." --prompt editor@v1`, then `--prompt editor@v2`.

> Ask your agent: *"Make the editor stricter about unsupported claims, as version 2 of its prompt, and show me how to compare it with version 1."*
