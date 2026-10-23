# Where `weave review --fix` holds back, and what a skill should do about it

This file collects every place where the fix engine is cautious, so that the `llm4j-workflow-guide` skill (and the other two) can say the right thing instead of promising more than a fix delivers. It is updated whenever a fix is added or a caution is lifted. The source of truth for the behaviour is the code and tests named in each row.

**The one-line version for a skill:** a fix is a mechanical edit that was checked to load and not add a high finding. It was **not** run, **not** evaluated, and **not** a judgement about what the workflow should do. After any fix: show the diff, run `weave check`, and run the tests or evals the project has.

## 1. When no fix is offered

| Situation | What review does | Why | What the skill should do |
|---|---|---|---|
| The statement shares its line with others (a one-line workflow: `workflow Main(q) { delegate … -> r  note "{r}" }`) | The finding appears, with no fix (`FixBuilderTest`/`FixApplierTest.aStatementOnALineWithOthersIsNotGuessedAt`) | An edit at "the end of the statement" would land after a `}` and break the script. Guessing where one statement ends on a crowded line is how fixes corrupt files | Offer to put each statement on its own line first (a pure reformat), review again, then fix. Or make the edit by hand from the finding's `fixHint` |
| An agent is declared over several lines and the thing to change is on a later line (`guard { pii: warn }` below `agent X {`) | LY02 has no fix | The fix looks on the agent's first line only | Read the agent, make the change, run `weave check` |
| The anchor text is not where the fix expects it (a different spelling of the same setting) | No fix; the rule's `weave rules test` fails if it happens on its own examples | A fix that cannot find its anchor is dropped, never approximated | Same as above |
| The rule's fix needs a choice (which model to downsize to, which version to pin, how big a memory bound) | The rule has no fix template (LC01, LC02, LG04, LP rules, LY01 and most others) | The right value depends on quality, cost and policy that review cannot see | Ask the user for the value, then write it. For LC02 propose `memory { max_tokens: … }` or a `context` block and ask about the size |
| The finding comes from the security audit (LA01 to LA20) | No fixes at all | Audit findings are decisions about what an agent may reach | Walk through the decision with the user (split the agent, add approval, accept in writing). Never auto-edit |
| The script fails to load | Review exits 2 and nothing is fixed | There is nothing to anchor to | Fix the load error first (`weave check`) |

## 2. When a fix is offered but needs judgement (REVIEW)

A REVIEW fix is shown by `--dry-run` and applied only by `--fix-rule R --yes` or `--fix-finding FP --yes`. `--fix` alone never applies it.

| Fix | Why it needs judgement | What the skill should do |
|---|---|---|
| **LR01 retry on a step whose agent can write or send** (`reviewWhen: agent.hasEffects`) | A retry runs the call again, so a tool that sends mail or writes a record can do it twice. Whether that is safe depends on whether the tool is idempotent or journaled (see LD02 and LD04) | Ask: "can this tool be repeated safely?" If yes, apply with `--yes`. If no, move the effect after a check, or use a journaled tool kind, and do not add the retry |
| **LR02 `on_failure { note "this step failed" }`** | The skeleton only records the failure. It does not recover, retry or tell a person | Ask what should happen when the step fails (ask a person, use a fallback agent, stop). Replace the skeleton with that |

## 3. Safe fixes that still change behaviour

These are applied by `--fix` because they are mechanical and local, but the workflow behaves differently afterwards. Say so.

| Fix | What changes | What the skill should do |
|---|---|---|
| **LR01 `retry 2 backoff 2s timeout 60s`** on a read-only step | A slow tool call is now cut at 60 s and tried up to three times in all. A step that legitimately takes longer will now fail | The values come from `fix.retry` in `review.properties`; ask if the project has a longer natural latency and set it before fixing |
| **LR03 `max 5`** on a loop | The loop now stops after five rounds. With no `on_exhausted` block it just ends, and whatever follows may use an incomplete result | Ask how many rounds the loop may need (`fix.loop.max`), and add an `on_exhausted { … }` that says what to do when the limit is reached |
| **LR08 `rate_limits { on_limit: suspend max_wait: 24h max_resumes: 50 }`** | A policy is now in force: runs suspend on a provider limit and give up after a day | It is a default, not a recommendation for every project. Offer `wait` or `fail` if the project wants them |
| **LY02 `pii: warn` → `pii: mask`** | The model no longer sees personal data it may have needed (an email address in a reply, a name in a greeting) | Run the agent's evals or tests after the change; if replies get worse, discuss `block` or what the agent really needs to see |
| **LM01 delete an unused agent** (whole block) | The agent is gone from this file. Review reads one script: another script that imports this one, a dynamic target (`delegate … to {name}`), or a Java host that looks the agent up by name will break. When a step names its agent dynamically, review reports the agent as "unknown" and does **not** raise LM01 | Before applying, search the project for the agent's name (other `.loom` files, imports, host code). Delete only when nothing refers to it |

## 3a. Accepting, baselining

| Action | Caution | What the skill should do |
|---|---|---|
| `--accept` | Needs a reason, and it is the **user's** reason. A reason made up by the agent is worse than no accept: it will be read in a pull request as the team's decision | Ask the user why. Offer the presets (false positive, intended here, handled elsewhere, will fix later) but write what they say. Prefer `--until DATE` for "will fix later" |
| Accepting several **serious** findings (high, or in security, durability, privacy) | Needs `--confirm N` typed back | Say the number and the rule aloud to the user before passing it |
| **LA01, the lethal trifecta** | Cannot be accepted in `review.properties`. Audit and Fabric read the decision in the script | Write `trifecta: accepted "reason"` in the script, with the user's reason, or split the agent |
| `--baseline-write` | Records the findings that exist now as "known". Serious findings are left out unless `--baseline-include-high` | Use it to adopt review on a big existing script, say that it does not fix anything, and say how many serious findings stayed open |
| `--strict-accepts` | Counts accepted findings against `--fail-on` | Suggest it for regulated projects, never turn it on silently |

## 4. How the engine itself limits fixes

| Behaviour | Where | Meaning |
|---|---|---|
| Fixes are applied most serious first; two fixes whose edits overlap, or that are on the same line, wait for the next pass | `FixApplier` | A pass can leave fixes "still pending"; run again or read the final review |
| At most 5 passes | `FixApplier.MAX_PASSES` | If findings keep reappearing, something is fighting the fixer. Stop and read the file |
| If the combined edits fail verification, they are tried one at a time and the failing one is withheld with its reason (printed as `Note:` on standard error) | `FixApplier` | Tell the user which fix was withheld and why; do not retry it blindly |
| Verification means: the new text loads, and no high finding appears that was not there before | `FixApplier.verify` | It does **not** run the workflow, the tests or the evals, and does not judge whether the behaviour is right |
| Candidates are written beside the script under a temporary name (`.review-candidate-…`) while they are checked, and removed afterwards | `ReviewSession.reviewText` | A read-only folder makes `--fix` fail with an error; nothing is changed |
| Only the script file is edited. Prompt files, `.loot` files and settings are never touched by `--fix` | `FixBuilder` | A finding about a prompt file (LP rules) has no automatic fix |
| Line endings and comments are preserved; only the lines an edit touches change | `SourceText` | Safe to run on a file with CRLF endings or comments |
| The file is replaced in one move after every pass succeeded | `ReviewFixing` | A failure part-way leaves the original file as it was |

## 5. Text a skill can use

> Review found N open findings. Some have a mechanical fix I can apply and check: it will load and add no new high finding. That is all the check promises: it does not run your workflow. After I apply fixes I will show you the diff and run `weave check` and your tests. A few fixes change behaviour (a retry timeout, a loop limit, masking personal data), so I will tell you what changes and ask about the values. Fixes that could repeat an action (retrying a step that sends mail), delete something other scripts might use, or decide what happens on failure, I will only propose, never apply on my own. I will not write a reason for you when you accept a finding. You can run the workflow whatever the findings say; they are advice.

## 6. Open items

- A formatter pass ("one statement per line") would remove the largest class of missing fixes (section 1, row 1). Not built.
- Multi-line agent edits (section 1, row 2) need an anchor that finds a setting inside a block. Not built.
- `createFile` fixes (a stub prompt file) are parsed but unused; the first rule that needs one will add the caution row.
