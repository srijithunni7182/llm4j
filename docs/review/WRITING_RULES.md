# Writing a review rule

A rule is a short YAML document over a small set of **facts**. Java computes the facts; the rule only says when to speak. You can add a rule to your project without writing Java, and it is checked when it loads.

```
weave rules new ACME-001 --pack my-rules     # a skeleton and two example scripts
weave rules facts                            # every fact you may read
weave rules check my-rules                   # is the pack sound?
weave rules test my-rules                    # do the examples behave?
weave rules add my-rules --dir .             # copy it in and pin its version and hash
weave rules explain ACME-001 --at main.loom:12   # why it did or did not fire here
```

## A pack

A pack is a folder: `pack.yaml`, `rules/*.yaml`, `examples/`.

```yaml
# pack.yaml
name: acme
version: 1.0.0          # semver, see "Changing a pack"
factModel: 1
rulesSchema: 1
prefix: ACME            # every rule id starts with it
rules: [rules/reliability.yaml]
```

Packs are never downloaded. You copy one into `review-rules/` and pin it in `review.properties` (`packs = acme@1.0.0 sha256:…`), so what runs is what was reviewed.

## A rule

```yaml
- id: ACME-001
  lens: reliability                    # one of the lenses
  severity: medium                     # high, medium, low or info
  about: step                          # the kind of thing it looks at: step, agent, tool, workflow, model, script, classifier
  when:                                # when it fires
    all:
      - { field: kind, is: delegate }
      - { field: agent.hasTools, is: true }
      - { field: retry, is: false }
  title: "{label} has a tool but no retry"        # {field} fills in a fact
  why: "A tool call can fail or hang."
  fixHint: "Add a retry to this step."            # every finding says what to do
  since: "1.0.0"
  references: [LLM06]                  # optional OWASP LLM Top 10 ids
  examples:
    fires: [ examples/acme-001-fires.loom ]
    quiet: [ examples/acme-001-quiet.loom ]       # both are required
```

### Conditions

The vocabulary is closed: there are no expressions, variables or functions, so a condition always finishes.

- Combine with `all`, `any`, `not`.
- Compare a fact with `is`, `isNot`, `in`, `notIn`, `gt`, `gte`, `lt`, `lte`, `contains`, `startsWith`, `matches`, `exists`, `missing`.
- Look into lists with `count: { gt: 0 }`, `some:`, `every:`, `none:`.
- The other side can be a literal, a list, `{ setting: data.regions }` (what the project declared) or `{ field: other.fact }`.
- A fact that cannot be known (a setting nobody declared) makes a condition **unknown**, never true. List the settings a rule needs under `requires: [setting.data.regions]`: without them the rule is reported as *not evaluated*.
- Wrap a rule in `heuristic: true` when it guesses from names or wording. It then runs only with `heuristics = on` or when the project names it.
- `escalate:` raises the severity when a second condition holds.

Run `weave rules facts` for the facts, with what each means. If the fact you need does not exist, it needs a small Java provider (see below); do not work around it with `matches` on text.

### Examples are mandatory

Every rule has at least one `fires` and one `quiet` example, and `weave rules test` runs them. An example is a file in the pack, or inline:

```yaml
fires:
  - { inline: "agent A { model: \"m\"  system: \"s\" }\nworkflow Main(q) { … }" }
  - { file: examples/x.loom, expect: 1, settings: { data.regions: "eu" } }
  - file: examples/needs-prompts.loom
    companions:
      prompts/writer/v2.md: examples/prompts/writer/v2.md   # files beside the script
```

Make the quiet examples the cases you are afraid of: the nearly-the-same script that must **not** fire.

## Fixes and options

A rule may offer one **fix** (for an edit with one right answer) and any number of **options** (when a person has to choose).

```yaml
  fix:
    safety: safe                       # safe, or review (needs --yes)
    label: "Add a retry"
    reviewWhen: { field: agent.hasEffects, is: true }   # make it "review" in this situation
    insertAfter: { anchor: statementEnd, text: " retry 2 backoff 2s" }
  options:
    - id: timeout-only
      label: "Time out only, no retry"
      explain: "A slow call is cut off once; nothing is repeated."
      safety: safe
      ask:
        - { name: seconds, prompt: "Time out after how many seconds", default: "60" }
      insertAfter: { anchor: statementEnd, text: " timeout {ask.seconds}s" }
```

Operations: `insertAfter`, `insertBefore`, `replaceText` (with `find:`), `deleteLines`, `createFile`. Anchors: `statementEnd`, `statementStart`, `beforeBrace`, `lineEnd`, `block`, `fileStart`, `{ lineOf: someFact }`. If an anchor cannot be found on the finding's line, no fix is offered: a fix is never guessed. Every fix and option is checked before it is written (the script loads, no new high finding), so write the edit as the smallest text that does the job. Use options for anything where the right value is the developer's call; the cautions are written down in the project's FIX_CAUTIONS notes.

## When Java is needed

Rules that need an analysis (which steps are independent, what the whole workflow costs) are still data rules: they read a **fact** whose provider is Java (`review/fact/`). Add the field to the fact model with a one-line description, compute it in a provider, and the rule reads it. A rule that must compare estimates across workflows (cost, time, effects) is a Java `FindingSource`. You can ship Java rules in a jar and name it with `--rules-jar`; nothing is loaded unless you name it.

## Changing a pack

Pack versions follow semver, and `weave rules diff` says what a change needs:

```
weave rules diff my-rules-1.0 my-rules-1.1 --on main.loom
```

It lists rules added, removed and changed, the **findings that would appear or disappear on your script**, and the smallest version the change needs:

| Change | Version |
|---|---|
| A rule removed, or a different fact model | major |
| A rule added, or a changed condition, severity, lens, fix or option | minor |
| Wording (title, why, hint, explanation) or examples only | patch |

It exits 1 when the new version is too small a step. `--markdown` prints a changelog entry.

## The loop

Propose a rule file, run `weave rules test` and `weave rules diff`, merge. A new core rule also gets a labelled script in the benchmark corpus (`ReviewCorpusTest` gates every rule at 0.9 precision and recall), a regenerated reference page (`-Dregenerate.rule.docs=true` on `RuleDocsGeneratedTest`), and a line in the changelog.
