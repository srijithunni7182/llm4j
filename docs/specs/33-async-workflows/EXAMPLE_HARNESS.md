# Example: a coding-agent harness in Loom

Status: **a sketch for the documentation; not a starter and not a script that runs.** It was written to show why phase 33 matters: the harness around a coding agent (permissions, a classifier, a stop check, compaction, steering,
sub-agents that run in parallel and are watched) is exactly the shape of workflow that phase 33 makes possible.

It is **approximate**. It was written from what could be observed of such a harness in use, not from any harness's code. The classifier is modelled as an evaluator, the sub-agents are one reading of what could be seen, and `plan.questions` in the new region is a placeholder that is never defined.
Companion documents: [DESIGN.md](DESIGN.md), [DECISIONS.md](DECISIONS.md) (the sketch with every decision applied), [PRINCIPLES.md](PRINCIPLES.md).

## What was checked, and what was not

- The sketch **without** its phase 33 region (the block between `NEW` and `END NEW`, and the `max_children` line) was run through `weave check --no-env` on 2026-11-12. It **parses**; only semantic findings remained:
  `run RepoState` is a task nobody has registered (it would be written in Java); the two policies have no test cases for their rules; `Explore` returns a model's text with no check; the rewind does not say what to do about side effects already done; the API keys are not set.
  Those are real to-dos for a real harness, and they show what `weave check` and `weave review` are for.
- The **phase 33 region was not checked**: it uses `start`, `wait`, `supervise` on a handle, `assert` and `max_children`, which do not parse until the phase is built. When it is built, this sketch is a candidate for a tutorial or documentation example and for a test that it checks (with the placeholder defined and the to-dos above done).
- It is deliberately **not a `.loom` file in the repository**: `RepositoryScriptsTest` loads every one, and this one cannot load yet. The code below is fenced as plain text for the same reason.

## How each piece maps to the harness it sketches

| In the sketch | In a coding-agent harness |
|---|---|
| `budget`, `max_children` | Limits on a session and on how many sub-agents run |
| `allow:` on `Shell`, `approve: [Shell]` | Tool permissions and permission modes |
| `Classifier` with the `Permissions` policy | A permission classifier that blocks a risky action |
| `context { compact }` | Summarising the conversation when it grows long |
| `run RepoState` and the loop | A stop check that will not let the work end until it is committed and pushed |
| `steerable`, `streaming` | A person steering the agent while it works |
| `checkpoint` and `rewind` | Going back after a failing test, carrying what was learned |
| `start`, `supervise`, `wait` (phase 33) | Sub-agents running in parallel with a watcher that steers, limits and cancels them |

One thing the sketch makes plain: a rule such as "an explorer may never run a command" is `assert never calls Shell { cancel }`, a rule written in the script and enforced before the call, where today it can only be done by not giving the agent the tool.

## The sketch

```text
// A sketch of the harness around me, in Loom. Approximate: written from what I observed in this session, not from the harness's code.
// Lines in a "// NEW" region use phase 33 (start, wait, cancel, supervise, assert) and do not parse today.

substitution: braces
shared_prompt: "Be concise. Follow the repository's CLAUDE.md. Report outcomes faithfully: a failing test or a skipped step is reported as it is."

budget { tokens: 2000000  calls: 400  warn_at: 80% }          // the session's limit; the harness enforces it, not the model
max_children: 4                                                // NEW setting (D14)

tool Files { use: file  root: "."  mode: readwrite }           // edit files inside the working directory only
tool Shell { use: shell  allow: "git, mvn, ls, grep, cat"  cwd: "."  timeout: 300s }   // an allow-list, never a string through a shell
tool Notify { use: webhook  url: env.NOTIFY_URL  format: slack  effect: changes }

evaluator Classifier { model: "claude-haiku-5-5" }             // the permission classifier: a separate, cheaper model
evaluator Reviewer   { model: "claude-haiku-5-5" }
policy Permissions: "permissions" checked by Classifier        // rules live in policies/permissions.md, not in a prompt
policy Honesty: "honesty" checked by Reviewer

agent Claude {
    model: "claude-sonnet-5-5"
    system: "You are a coding agent. Work in small steps, test before you claim success, ask when a decision is the user's."
    tools: [Files, Shell]
    approve: [Shell]                                           // permission modes: a person's yes for a risky tool call, journaled
    steerable: true                                            // the user can steer me mid-task
    streaming: true
    guard { pii: mask }
    context {                                                  // compaction when the conversation grows
        compact: chain(trim_tool_results, summarize, drop_oldest)
        summarizer: "claude-haiku-5-5"
        trigger: 80%
        target: 55%
        keep_recent: 6
    }
}
agent Explorer {                                               // a read-only sub-agent for broad searches
    model: "claude-haiku-5-5"
    system: "Search and report what you find. Change nothing."
    tools: [Files]
    steerable: true
    streaming: true
}

workflow Explore(question) {
    delegate "{question}" to Explorer -> found
    return found
}

workflow Session(request) {
    checkpoint planned
    set tasks = "none yet"

    loop until (state.finished == "yes") max 40 {
        delegate "Request: {request}\nTasks: {tasks}\nDo the next step." to Claude -> step expecting { summary: string, tests: enum["pass", "fail", "none"] }

        alt (step.tests == "fail") {                           // a failing test sends the work back, with what was learned
            rewind to planned when (step.tests == "fail") at most 2 times carrying feedback = "{step.summary}"
        }

        // === NEW (phase 33): delegate broad searches to children, watched, in parallel ===
        for each q in plan.questions max 3 {
            start Explore(question = q.text) timeout 10m budget { tokens: 80000 } -> found[q.name]
            supervise found[q.name] budget { tokens: 10000 } {
                when tokens > 70000 { cancel use partial }
                when idle > 2m      { steer "You seem stuck. Narrow the search and report what you have." once }
                assert never calls Shell { cancel }            // an explorer may not run commands: refused before it runs
                assert "searches only for the question and reads real files"
                    using Reviewer every 2m max 3 { steer "Stay on the question." once }
            }
        }
        wait all found timeout 15m -> findings
        // === END NEW ===

        run RepoState() -> state                               // a task, not an agent: is the work committed and pushed? (the "stop hook")
        alt (state.dirty == "yes") {
            note "Not done: uncommitted work remains."          // the loop goes round again
        }
    } on_exhausted {
        halt "The step limit was reached with work still open."
    }

    set text = step.summary
    check text against Honesty using Reviewer on_violation { rewrite with Claude }           // faithful reporting, checked at the edge
    check text against Permissions using Classifier on_violation { rewrite with Claude }
    use Notify(text = text) approve "Tell the user the result?" -> receipt timeout 5m
        on_declined { return text }
    note "Notified: {receipt}"
    return text
}
```

## The two policy files it expects

`policies/permissions.md`

```text
# Permissions
## no_force_push
Never force-push or rewrite shared history.
## no_secrets
Never print, send or commit a secret.
```

`policies/honesty.md`

```text
# Honesty
## report_failures
A failing test or a skipped step is reported as it is, never as success.
```
