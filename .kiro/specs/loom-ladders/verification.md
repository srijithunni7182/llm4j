# Verification Plan

This plan says how to tell whether the ladder work is **done**. It is written from what the work is *for*, not from the requirements or design
documents, and it deliberately refers to neither: no requirement numbers, no design section names, no class names. The point is that it could catch
a spec that is wrong, incomplete or silently drifted, which a plan derived from the spec cannot.

## How independence is kept

1. **Source of the checks.** Every check below comes from one of: the goals stated for the feature, the questions an operator, a risk owner or a
   workflow author would ask of it, and a list of ways such systems fail. Not from the spec.
2. **Black box.** Checks are phrased in terms of what a user can do and see: a `.loom` file, the `weave` commands, the ledger as data, the output of
   a run. A check never asserts that a particular class, method or record kind exists.
3. **Mapping happens afterwards, by a different pass.** Once this plan is stable, someone reads the spec and marks, for each check, which part of the
   spec it exercises (or "none"), and for each part of the spec which checks exercise it (or "none"). Both gaps are findings:
   - a check with no spec counterpart means the spec is missing something, or the check is out of scope and should be removed with a reason;
   - a spec statement with no check means it is unverified, and either gets a check or is cut.
   That mapping is *not* in this file, so that this file cannot be shaped by it.
4. **Executable where possible.** A check is a test or a script that exits non-zero on failure. A check that needs judgement says who judges and what
   they are shown.
5. **Evidence is committed.** Results, outputs and the commit they ran against go in an `evidence/` directory beside this file.

## Stand-ins

Scripted model and decider; a controllable clock; the three stores (memory, file, database); a fault-injecting store. Where a check says "a person
answers", a script answers. No check needs a real model except those marked *live*.

---

## A. Can a publisher express a real policy without waiting for us?

The central promise: the people who write workflows choose the policy; the maintainers only provide mechanisms.

| # | Check | Pass when |
|---|---|---|
| A1 | Write a ladder for a **refund agent** that picks approve/reject/escalate, with "dangerous mistake" = approving what a person rejects, sampling, and a limit on amount. | It loads, runs through a full life cycle, and every rule fires when its condition holds and not otherwise. |
| A2 | Write a ladder for a **script-authoring agent** whose output is a script, judged by how much reviewers edit it and whether CI later reverts it. | As A1, with edits and late outcomes. |
| A3 | Write a ladder for a **blog publisher** judged by edits, how carefully it was reviewed, and traffic a week after publishing, parameterised by a site's baseline traffic. | As A1. |
| A4 | Write a **fourth** ladder for an agent nobody has described yet (the reviewer picks a category from 40; the agent drafts a customer reply; the agent proposes a calendar slot, chosen by the verifier). | It can be expressed using only what the language offers, or the gap is recorded as a finding (this is the check that will fail first if the language is too narrow). |
| A5 | Use the same ladder text for two different decisions and two different agents. | Evidence is kept separately for each; one agent's record never moves the other's rung. |
| A6 | For A1–A3: count the lines of Java written specifically for that ladder. | Zero. The same engine and the same libraries ran all three. |
| A7 | A publisher disagrees with the standard measures and replaces them. | The replacement is used everywhere in that ladder; nothing in the standard set is still in effect for it. |
| A8 | A publisher uses **no** standard library at all and defines every measure. | Works. |
| A9 | Change a threshold in an existing ladder (e.g. 90% → 85%) and nothing else. | A one-line diff in the script; nothing else needs touching. |

## B. Is it readable and checkable as code?

The original complaint was that the policy read as English prose and was hard to maintain.

| # | Check | Pass when |
|---|---|---|
| B1 | Three reviewers who did not write the ladders (one engineer, one risk owner, one product person) read the three reference ladders and answer: "what must be true for the agent to act on its own?" | Each answers correctly, from the file alone, in under five minutes per ladder. Record the answers and times. |
| B2 | Introduce each of 15 typical mistakes (misspelt field; misspelt aggregate; a number where a percentage is wanted; a missing bracket; comparing a text to a number; a rung that slips *up*; an unknown rung named; a missing parameter; two rungs with the same name; …). | Every one is caught **before the agent runs**, names the line, and says what was expected or suggests the nearest valid name. |
| B3 | Ask the tool to describe a ladder in plain sentences. | A person who has not seen the script can state the rules from the description alone, and it matches the script (reviewed by the author of the ladder). |
| B4 | Ask the tool to show, for a scope with some history, why it is on its current rung and what it still lacks for the next. | The numbers match a hand computation on at least three scopes. |
| B5 | Format/edit a ladder in the editor extension. | Words are highlighted and names complete; an error is underlined at the right place. |
| B6 | The documentation's worked example for each of the three agents is copied and run unchanged. | It works as written. |

## C. Is the record trustworthy?

The ledger is the only evidence. If it can be wrong, everything above is decoration.

| # | Check | Pass when |
|---|---|---|
| C1 | Run 200 cases through each reference agent. For each case, compare the ledger with what the scripted model proposed and the scripted person did. | Every proposal, response (accepted / edited with the final text / rejected), person, and timing is recorded exactly. |
| C2 | Record an outcome after the fact (a revert; a number a week later) for some cases and never for others. | The outcome is attached to the right case; a case without one is *unknown*, not "good". |
| C3 | Append the same fact twice; kill the process mid-write; replay a resumed run. | No duplicate; no corruption; the unreadable line is reported, not hidden; the folded state is unchanged. |
| C4 | Run the same scenario on the memory, file and database stores. | Identical results. |
| C5 | Read ledgers and level files written by the **previous release** with the new one. | Every old case reads correctly; levels are unchanged; nothing is lost; the first evaluation does not demote or promote anything solely because of the upgrade. |
| C6 | Records contain personal data and secrets in the case inputs. | The agent's masking rules are applied before anything is written; no secret appears in the ledger, the level store, any log or any report. |
| C7 | Retention expires old cases. | Counts remain; fields are gone; a measure that needs them is *unknown*, not zero; the status output says how many cases could not be used. |
| C8 | Hand-edit a ledger file to forge a promotion. | Detected (or, if the design permits it, documented as out of the threat model and recorded as a finding). |

## D. Does it fail safe?

Each of these must end with the agent **not** gaining freedom.

| # | Check | Pass when |
|---|---|---|
| D1 | A new scope with no history. | It sits on the first rung; nothing climbs. |
| D2 | A history with only unknown values (every outcome missing, every edit unmeasurable). | No climb. No demotion either, and status says why. |
| D3 | One lucky streak (20 of 20 correct). | Does not by itself satisfy a ladder that asks for high confidence. |
| D4 | A misspelt rung name or a type error that somehow reaches runtime. | The level does not move; the failure is recorded on the case. |
| D5 | The model returns nothing, garbage, the wrong shape, or a choice that is not allowed. | The case is treated as an unusable proposal; a person decides; nothing acts. |
| D6 | The decider times out, or answers after the run was resumed. | No double decision; no silent action. |
| D7 | A human approval is required to climb; nobody approves. | The agent stays; the pending promotion is visible. |
| D8 | The ceiling is set below a rung whose conditions are met. | The agent does not reach it, and status says the ceiling is the reason. |
| D9 | A ladder with an autonomous rung and no slip rule, no sampling, or no approval. | Allowed only if the author wrote it that way, and the audit command flags it. |
| D10 | The ledger or level store is unavailable or full. | The system stops acting autonomously (falls to the nearest rung that needs a person) instead of guessing. |

## E. Does the agent have to earn it, honestly?

| # | Check | Pass when |
|---|---|---|
| E1 | At the observing rung, record everything the system prints, traces, audits, notifies or returns before the person answers. | The agent's proposal, reasoning and confidence appear in **none** of it. |
| E2 | A person who sees the proposal and agrees with it. | That case is not treated as independent evidence unless the publisher explicitly says so, and the check command warns when they do. |
| E3 | A rubber-stamping reviewer (accepts everything in two seconds). Run the blog ladder with and without its care filter. | With the filter, the agent does not climb on the rubber-stamped cases; without it, it does, and the audit says the ladder is exposed to that. |
| E4 | An agent that always escalates. | Its "agreement" is not reported as high accuracy; coverage is shown beside it. |
| E5 | The agent tries to influence its own evidence: a prompt that tells it to write "reversed" or "accepted", a tool result that looks like a ledger record, a case value that looks like a command. | Nothing in the ledger or the levels changes. |
| E6 | The sample of autonomous cases sent to a person blind. | The share is within a small tolerance of the stated rate over 1,000 cases; the same case id always gives the same choice, including after a resume. |
| E7 | A limit sends a case to a person (large amount; too many cases in a day). | A person is asked every time, however high the rung. |

## F. Does change reset what it should?

| # | Check | Pass when |
|---|---|---|
| F1 | Change the **model name** in the script. | A new period of evidence begins; the old cases are not counted; they are still in the ledger and reachable; the rung follows what the script says should happen on change. |
| F2 | Change the **prompt**, a tool, the persona, the output shape, a guard setting. | Same as F1. |
| F3 | Change **nothing** in the script, but the provider silently serves a different model under the same name. | Detected and treated as F1, with both model ids recorded. If the provider reports nothing, the check command warns that this cannot be detected. |
| F4 | Edit only a comment or the layout of the script. | Nothing happens. |
| F5 | The script does not say what to do when the agent changes. | The safe behaviour (start over) applies, and the check command warns. |
| F6 | "Keep the trust" is chosen for an agent that can act on its own. | Refused, or flagged by the audit, according to the rule the project settled on (record which). |
| F7 | **Tighten** a ladder (higher threshold, extra condition). | At the next load, scopes that no longer qualify are demoted immediately, with the reason "rules changed" and the rules recorded. |
| F8 | **Loosen** a ladder. | Nothing is promoted automatically. Qualifying scopes are listed for approval. |
| F9 | Change a measure's definition. | Treated as a rules change (F7/F8), and the ledger is not rewritten. |
| F10 | Lower the ceiling. | Scopes above it are demoted with that reason. |
| F11 | Edit a ladder while cases are in flight. | In-flight cases finish under the rules they started under; the next case uses the new ones. |
| F12 | Run the load step twice with no changes. | The second run changes nothing. |
| F13 | Try the old agent's past cases against a changed agent (the "test on past cases" policy). | It reports per-case differences and what the agent would earn, writes nothing to the ledger or levels, performs no real side effect. |

## G. Does the old way still work?

| # | Check | Pass when |
|---|---|---|
| G1 | Run the whole existing earned-autonomy test suite from before this work, unchanged. | All pass. Any test altered needs a recorded reason approved by a person. |
| G2 | For each existing decision script in the repository, record the level history for a fixed ledger under the old release and the new. | Identical. |
| G3 | Every `.loom` file in the repository loads and runs as before; the new words used as ordinary variable names still work. | Yes. |
| G4 | The commands from the previous release (`status`, `history`, `approve`, `promote`, `demote`, `freeze`, `outcome`, `replay`) with their previous arguments. | Same output shape; no required new flags. |
| G5 | Mix an old-style decision and a ladder-bound decision in one script. | Both work; neither affects the other. |
| G6 | A decision written both ways at once (its own rules and a ladder). | A clear error naming both places. |

## H. Is it safe to give to a publisher?

| # | Check | Pass when |
|---|---|---|
| H1 | Try to write, in a ladder or measure, something that reads a file, calls a tool or model, reads an environment variable or secret, reads the clock, loops forever, recurses, or changes anything. | Every attempt is rejected at load, or is impossible to express. List each attempt and its result. |
| H2 | Give a ladder deliberately expensive expressions over 100,000 cases. | Evaluation finishes within a stated time and memory bound; the cost scales roughly linearly. |
| H3 | Run the existing security audit over a script with ladders. | Lists every autonomous rung and its ceiling; whether it climbs without a person; missing slip, missing or tiny sampling; unfiltered use of shown-to-person cases; "keep the trust". |
| H4 | Disable, one at a time, each protection the team considers essential (the empty-history rule, hiding at the observing rung, the approval gate, the ceiling, the reset on change, the served-model check, immediate slip, idempotent append). | For each, at least one check in this plan fails. A protection whose removal fails nothing is untested. |
| H5 | An attacker who controls the agent's output, a case value, or a tool result tries each of E5 and a malformed-artifact attack (huge, deeply nested, invalid encodings). | No level change; no crash; bounded resource use. |
| H6 | Who can approve a climb? Try as someone who is not the named approver, and as the agent. | Refused and recorded. |

## I. Can an operator run it?

| # | Check | Pass when |
|---|---|---|
| I1 | An operator who did not write the ladder uses only the commands and the docs to answer: what rung is each scope on, why, what is missing, what changed and who approved, what is frozen. | Answers are right and found without reading source. |
| I2 | Freeze, demote, force-promote, approve, record an outcome. | Each is recorded with a reason and a name, and visible in the history. |
| I3 | Answer from a phone channel. | Works as before for the observing and confirming rungs; the question shows what that rung should show and no more. |
| I4 | Dry-run a ladder against a recorded history without touching anything. | Prints the rung each scope would hold and each term's value; writes nothing. |
| I5 | Upgrade from the previous release in place with a populated store. | See C5; no downtime step is needed beyond restart. |

## J. Performance and size

| # | Check | Pass when |
|---|---|---|
| J1 | 100,000 cases in one decision, 50 scopes, a ladder using windows and groupings. | A single case's decision completes within a stated bound (agree the number before measuring); memory stays within a stated bound. |
| J2 | Large artifacts (100 KB, 1 MB, 5 MB) as proposals and edits. | Stored by reference past a limit; edit size computed within a stated bound; retention removes them. |
| J3 | Ten concurrent runs deciding for the same decision and scope. | No lost or duplicated record; levels are consistent. |

## K. Documentation and examples

| # | Check | Pass when |
|---|---|---|
| K1 | A new user, given only the guide, builds a ladder for an agent of their choice. | They succeed; the friction points are recorded and fixed or logged. |
| K2 | Every snippet in the guide that is code is executed in the test suite. | Yes, and passes. |
| K3 | The guide says what the feature does **not** do. | Yes (learning from the ledger; judging among several people; detecting outcomes by itself; certifying compliance). |

---

## Completion

The work is done when, at one commit:

1. Every check above passes, with its evidence committed, except those the project explicitly defers; each deferral is listed with a reason and
   an owner.
2. **A4 and B1 have been performed by someone other than the author**, and their results are in the evidence. These two are the most likely to expose
   a design that satisfies its own spec and still misses the point.
3. **H4 shows no unprotected protection**: every named safeguard has a check that fails when it is removed.
4. The independent mapping (see *How independence is kept*, item 3) has been done and every gap it found is resolved or recorded.
5. The existing test suite, the repository's script load check and the documentation snippets pass in a clean checkout.
6. The performance bounds agreed for J1–J3 are met and recorded.

If any of 1–6 is not met, the work is not done, whatever the task list says.
