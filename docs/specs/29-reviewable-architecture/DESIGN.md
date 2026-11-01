# Phase 29 design: architecture you can review

Status: **draft for review** (decision D30 recorded 2026-11-01). Layer: documentation, probes and the documentation tests. No change to the language or the runtime. Part of the [engineering discipline program](../DISCIPLINE.md); it comes **after** [phase 28](../28-evaluation-first/DESIGN.md), because it describes what phases 24 to 28 built, and it owns the program's one update of the stack-level pages (moved here from phase 28 R9).
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Depends on phases 24 to 28 being built and verified. Follows the evidence rule of [Why teams choose llm4j and Loom](../../WHY_LLM4J.md) and its [probes](../../../probes/README.md).

## 1. Problem

The owner has built multi-agent workflows with LangChain, LangGraph and Google ADK, and with Loom. The difference that matters most in that experience is not a feature: **Loom distils workflow best practice into a language that can be reviewed, by people and by tools.** In a code-first framework the workflow is general-purpose code, so its architecture (which node sees what, which call changes something, where a person is asked, what is checked before it leaves) is spread through the plumbing: a reviewer must read the plumbing, and a tool cannot analyse arbitrary code. In Loom the workflow is a constrained language, so the architecture is something a reviewer can read and a tool can check. Phases 24 to 28 rest on exactly that: least-privilege context, policy checks at the exits, tool effects and the evaluation gate can be enforced only because the workflow is a language.

Nothing in the documentation says this plainly today. The README and [WHY_LLM4J.md](../../WHY_LLM4J.md) list guarantees (crash safety, budgets, tests, review) but not the idea that connects them, and not the five architecture mistakes the program now catches.

## 2. Goals and non-goals

Goals:

- One page that states the differentiator in the owner's words and shows it, mistake by mistake, with real Loom and real `weave` output (R1, R2).
- Every statement about another framework is **backed by a probe** that anyone can rerun, as the existing comparison page already requires (R3, D30).
- The claim leads where newcomers start: the README, the stack pages, the tutorial, `llms.txt` (R4, R5).
- The page cannot drift from the code (R6).

Non-goals:

- Marketing copy without evidence, or claims about a framework that no probe checked.
- Rewriting the owner's personal note ([WHY_LOOM.md](../../../src/loom/ai-agent4j-loom/WHY_LOOM.md)); it gains at most a "see also" link, with the owner's approval.
- A general comparison of frameworks beyond the five mistakes and the existing probes.

## 3. Requirements

**R1 The page: `docs/REVIEWABLE_ARCHITECTURE.md`, "Architecture you can review".** In the voice of `WHY_LLM4J.md` (short sentences, a table first, evidence linked):

1. **The claim**, first, in the owner's words: *Loom distils multi-agent best practice into a language that can be reviewed, by people and by tools. In code-first frameworks the architecture is a convention; in Loom it is checked.*
2. **Why a language makes it possible**: three short paragraphs on what a tool can know about a Loom script that it cannot know about general-purpose code (declared agents, tools and their effects, the flow of values, the exits), with one picture: the same small workflow as a Loom script beside its `weave graph`.
3. **The five mistakes**, one table row and one short section each (from [DISCIPLINE.md](../DISCIPLINE.md) §1):

| Mistake | What Loom does | What you see |
|---|---|---|
| The god prompt | Policies in their own file, checked at the exits by an evaluator; agents' changing tool calls checked automatically | `weave check`'s sink error; LY07; the leak guard |
| The JSON trap | Reason in prose, then an `extractor` | LP10 and its split fix |
| Evaluation as an afterthought | The dataset is the requirements; untested evaluators and extractors do not pass `weave check` | the gate's error; LT06; `weave eval --requirements` |
| HITL mid-reasoning | `use … approve` at the edge; tool effects; earned autonomy as an option | LH05; LH03 counting approvals |
| Global state leakage | Internal nodes see only their inputs; `transcript:` on agents; the transcript in the audit | LP08, LP09, LA21 |

   Each section shows the mistake written in Loom, the exact `weave` output that names it, and the fixed script. Each links to the tutorial chapter and the `LOOM_GUIDE.md` section that teach it, and to its probe (R3).
4. **What is not enforced, said plainly**: whether an agent's words reach people, and what a prompt means, are judgements the language cannot make; the skills teach them (D11). A Java task is trusted code. A declared effect is the developer's word.
5. **Where to go next**: the tutorial, the workflow guide, the skills.

**R2 Show the review, not just the rule.** For each mistake the page shows a side-by-side: on the left, the Loom script as a reviewer reads it (short, with the architecture visible: `extract … from`, `check … against`, `use … approve`, `effect: changes`); on the right, what `weave check` or `weave review` prints for the broken version. The point made in one sentence per section: *a reviewer can see this in ten lines, and so can the tool.*

**R3 Probes for the claims about other frameworks** (D30). One new probe per mistake, in the existing [probes](../../../probes/README.md) format (fake models and tools, no API money, versions pinned, a `run.sh` per side, results in the probes README), run on **Loom, LangGraph, LangChain and Google ADK** (the frameworks the owner has used):

| Probe | The question it answers, the same way for each framework |
|---|---|
| P9 | A utility step that pulls fields out of text: can it be given only its inputs, so nothing earlier in the run reaches it? Does anything notice, before the run, when it is not? |
| P10 | A step that uses tools and must answer in a large nested schema: does anything notice before the run? |
| P11 | A reply that breaks a written rule: can it leave the workflow without a check? Does anything notice, before the run, that no check stands before an exit? Is an agent's own sending tool call checked? |
| P12 | A sending tool that needs a person's yes: where does the run pause (in the middle of the agent's loop, or at the edge)? Does anything count the questions one run can ask, before it runs? |
| P13 | An evaluator or guardrail that enforces a rule: does anything stop it being used with no test of it? |
| P14 | A tool that posts: is "it changes something" stated once on the tool and honoured by approvals, retries and tests? |

- Each probe records exactly what was found, including where another framework does well (a guardrail hook, an interrupt, a schema helper), and reports "found no …" only after searching the installed packages and their documentation, with the versions named, as P6 already does.
- The page states nothing about another framework that is not in a probe result. Where a probe's answer is "yes, with code you write", the page says so.
- Versions are pinned in each probe's `requirements.txt` and named at the top of the probes README; a probe is rerun before each release that changes the page.

**R4 `WHY_LLM4J.md` points to it.** The comparison table gains rows for the five mistakes, each one line with its probe link, and a line at the top that links the new page as "the idea behind these guarantees". The existing rows are unchanged.

**R5 The stack-level pages, once for the program** (moved from phase 28 R9). The new constructs and the claim, a sentence each, in: the root `README.md` (the claim near the top, under the title, linking the page), `wiki/Home.md`, `docs/README.md`, `docs/THE_STACK.md`, `DEVELOPER_GUIDE.md` (the "why" section), the [tutorial README](../../tutorial/README.md) ("Where to go next"), `llms.txt` (the claim and the five mistakes in machine-readable form, for models that read the project), the VS Code extension's `README.md` (its marketplace text), and the `llm4j-workflow-guide` skill ("Explain as you go" points to the page when a user asks why Loom works this way). [WHY_LOOM.md](../../../src/loom/ai-agent4j-loom/WHY_LOOM.md) gains a "see also" line only if the owner agrees (§7). The program is marked done in [DISCIPLINE.md](../DISCIPLINE.md) and the roadmap.

**R6 The page cannot drift.** `ReviewableArchitectureDocsTest`:
- every ` ```loom ` block on the page loads (or fails with the error the page shows, for a `loom-error` block);
- every `weave` output shown is **generated by the test** from the block beside it and compared with the page, so a change to a message or a rule shows up as a failing test, not a stale page;
- every rule id the page names exists in the rule catalogue and every probe link resolves to a section of the probes README;
- every framework name on the page appears only in sentences that link a probe.

## 4. Compatibility

Documentation only. No behaviour changes.

## 5. Security notes

- Probes use fake models and fake tools, no keys, and no network beyond installing pinned packages into a temporary virtual environment, as the existing probes do.
- Examples on the page use obviously fake values (`test-key`, `example.com`).

## 6. Decisions

| # | Decision |
|---|---|
| D30 | The differentiator is documented after phase 28 as its own phase: one page, the stack-level pages once, and **every claim about another framework backed by a rerunnable probe**, following `WHY_LLM4J.md`'s rule. |

## 7. Open questions

1. Should `WHY_LOOM.md` (the owner's personal note) link the new page? Proposed: a single "see also" line at the end, only with the owner's approval; the note's words are not changed.
2. Google ADK has Python and Java versions. Proposed: probe the Python ADK (the one the owner used), pinned, and say so on the page.
3. Should a short article version go in `docs/articles/` for posting elsewhere? Proposed: yes, written from the page after it is verified, with the same probe links.

## 8. Documentation affected

The new `docs/REVIEWABLE_ARCHITECTURE.md`; `probes/README.md` and six new probe folders; `docs/WHY_LLM4J.md`; the stack-level pages, skill and extension README in R5; `docs/README.md`'s index; the release notes (a line pointing to the page).
