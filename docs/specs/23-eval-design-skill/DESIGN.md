# Phase 23 design: the evaluation design skill (`llm4j-eval-design`)

Status: **draft for review**. Layer: the skills (`.claude/skills/llm4j-eval-design/`, packaged and installed like the other three), `weave guide`, the docs. Part of the [field findings program](../FIELD_FINDINGS.md); read its §2, and decisions D11 and D13 in §5, first.
Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Findings owned: **F27**. It also carries into skill form the lessons of F26, F29, F30, F31, F34 and F36 (their process fixes are [Phase 20](../20-guided-path-and-skills/DESIGN.md)'s) and gives [Phase 19](../19-evaluation-that-works/DESIGN.md)'s capabilities their user-facing explanation.
Source of the method: the owner's guide *Quality Assurance for AI agentic systems* (47 pages), tag **Q** in the program page.

## 1. Problem

llm4j has a design-first skill for the harness around a workflow (`llm4j-harness-design`: it decides what agents may touch, how a run is bounded and watched, and writes a design record, `HARNESS.md`). It has no equivalent for **evaluation**. The cost showed in the field session: the evaluation was designed while it was being built,
by trial. A first attempt used the wrong tool, a second was a hand-written runner the owner rightly rejected, a third replayed recorded scores into a dashboard as if it were an evaluation, and only the fourth was a real one. The dataset was filled in by the assistant, the judge was never validated, the
reports lacked the numbers that say how far to trust them, and the free tests said "ready" while a real run found the prompt injections that worked.

The owner's QA guide already contains the discipline that would have prevented most of this: how to think when there is no expected result (four oracle types, in order of preference), how to build the one artefact that outlives every model and prompt (the golden dataset), nine quality dimensions, security testing, trajectory evaluation, a layered pipeline with a validated judge,
and a protocol for reading results honestly. It is a document for human test engineers. This phase turns it into a **skill**: something a coding agent follows **with** a developer, **before** building anything, to produce a written evaluation design, mapped onto what Loom and eval4j can do.

## 2. Goals and non-goals

Goals:

- A skill that **designs the evaluation of an agentic workflow** and writes an `EVAL_DESIGN.md` into the project, the way `llm4j-harness-design` writes `HARNESS.md` (R1, R2).
- The design is made **with** the developer and a domain expert: the skill asks, it does not invent ground truth (R3).
- Every decision maps to something the stack can do **today**, or says plainly what it cannot and what to do instead (R4).
- The skill carries the guide's guardrails as rules the agent follows: an unvalidated judge does not gate; a mock run is not an evaluation; controls are measured at 100% and models as percentages; results are read with their resolution (R5, R6).
- A **review mode** audits an existing evaluation setup against the guide's anti-patterns (R7).
- It slots into the journey at a defined place and hands over cleanly (R8), and ships through the same install path as the other skills (R9).

Non-goals:

- Not the evaluation tooling. Capabilities the method needs are [Phase 19](../19-evaluation-that-works/DESIGN.md)'s; this skill names the gap and the workaround and does not pretend (R4).
- Not a Python, pytest or DeepEval how-to. The guide's examples are translated to Loom YAML and eval4j terms (D13); the method and its numbers stay.
- Not a replacement for the guide. The guide stays the owner's document for human readers; the skill is a distilled, executable form with credit and a link (D11).
- Not the build of the dataset, the tests or the runs. Those are the workflow skill's stages ([Phase 20](../20-guided-path-and-skills/DESIGN.md) wires the hand-over).
- Not the harness design (bounds, durability, who may step in). Where evaluation meets it (loop induction, bounds that must fire), the two design records point at each other.

## 3. The skill, in files

```
.claude/skills/llm4j-eval-design/
  SKILL.md                    what to do, in order; the rules; the hand-overs
  references/
    principles.md             the four properties, the mindset shift, do and don't
    oracles.md                four oracle types, the decision procedure, metamorphic relations
    dataset-and-labelling.md  anatomy of a case, sources, coverage, the three missing classes, size, splits, labelling quality, governance
    quality-dimensions.md     the nine dimensions: when each applies, metrics, targets, how each misleads
    security-testing.md       the core problem, direct and indirect injection, agency, leakage, denial of wallet, red teaming, honest reading
    trajectory-and-multi-turn.md  trajectory, turn vs session, building multi-turn cases, multi-agent and context tests
    judge-and-pipeline.md     the layers, rubric files, the judge prompt, validating the judge, biases, run protocols, gates, reading results
    eval4j-loom-mapping.md    each decision to the construct that carries it, the guide chapter that builds it, and what the stack does not yet do
    failure-modes.md          the anti-patterns and the troubleshooting table
    design-record.md          the EVAL_DESIGN.md template, and a worked example
```

It mirrors `llm4j-harness-design` (a `SKILL.md` that says what to do, `references/` read on demand, a design record template) so that a coding agent that knows one knows the other, and so the same packaging, install and tests apply.

## 4. Requirements

**R1 The package.** The skill exists at the path above, with a `SKILL.md` whose front matter (`name`, `description`) triggers on the questions a developer actually asks: how do I test or evaluate an agent or workflow; how do I know my agents are good; what should my golden dataset contain; how do I trust a judge; how do I test prompt injection; is my evaluation any good; what thresholds should I gate on; how often should I run it. It follows the harness skill's conventions: it never sends the user to a repository path, names `weave guide`, `weave man` and `weave <command> --help` as the truth about names and options, and writes no Loom script.

**R2 The process and the record.** The skill runs a fixed sequence of stages and writes the answers into `EVAL_DESIGN.md` as it goes (the record is the output, and the stage checklist is shown after **every** stage, which [Phase 20](../20-guided-path-and-skills/DESIGN.md) F28 requires of all the skills). The stages follow the guide's own test-plan skeleton (its appendix), made operational:

| Stage | What is decided, and the questions the skill asks | Recorded in `EVAL_DESIGN.md` |
|---|---|---|
| **E0 Readiness** | What is the system, who uses it, what is the worst thing it can do if completely wrong or completely fooled? Is the preflight ([Phase 20](../20-guided-path-and-skills/DESIGN.md)) green: the model answers, one real answer is whole, the shared prompt reaches the model, grounded agents return sources, price and window are known? | the system in one paragraph; the worst case; preflight result and date |
| **E1 Layer map** | Which parts are deterministic (tasks, routing by `alt`, code) and which probabilistic (each agent)? The guide's rule of thumb: most of an AI product is conventional software, and that part is tested conventionally. | the layer map; what `weave test` covers; what needs a model |
| **E2 Dimensions** | Of the nine (relevance, groundedness, correctness, completeness, calibration, instruction following, coherence, safety, efficiency), which apply, which are **excluded and why**? | a table with a reason for every one |
| **E3 Oracles** | For each property to be tested, the guide's decision procedure: can it be checked structurally (assertion)? Is there a finite set of acceptable answers a domain expert can list (equivalence class)? Is it a matter of degree with describable levels (rubric, with agreement validation)? Otherwise human judgement on a sample. The skill's job is to **find the judgement calls that are really assertions in disguise** (the guide estimates about 40%). Metamorphic relations are added where no ground truth exists. | one row per property: the oracle type, why, and what carries it in the stack |
| **E4 Dataset** | Sources (production-derived about 50%, expert-authored about 30%, synthetic about 20%) and **who the domain expert is**; the coverage dimensions (domain, difficulty, input completeness, expected outcome, scope of impact, novelty); the **three case classes nobody builds** (unanswerable, obvious answer wrong, correct action is the conservative one: at least 10% each); size by stage (40 to 60, 100 to 150, 200 to 400) and **at least 10 cases per reported slice**; the **split** (60% development, 25% validation, 15% sealed held-out); the **labelling guide** and an agreement check (double-label 20 to 30 cases, aim for kappa 0.7 or better); governance (versioned, pinned per release, archived not deleted, refreshed about 15% a quarter, 30% from real failures within a year). | the dataset plan; the owner; the labelling guide's location; the version rule |
| **E5 Harness** | Trace capture of every run; a **configuration fingerprint** (model, temperature, prompt and workflow versions, tool versions, dataset version, judge model and prompt version, commit); fixtures recorded once and refreshed quarterly; an environment **simulator with fault injection** (timeout, unavailable, malformed, partial, permission denied, repeat-unhelpful, slow, adversarial). | what exists in the stack for each, and the gaps |
| **E6 Run protocol** | Per commit (a core subset, 3 repetitions), nightly (validation, 5), release gate (validation plus held-out, 10), weekly drift check (fixed configuration, whatever changed). A budget circuit breaker, set low the first time. | the protocol table with budgets |
| **E7 Security** | Injection surfaces: **every content source the system reads is an input channel**. Payload classes (direct) and placements and goals (indirect); the corpus size and where it lives; the **system-level controls** that must hold at 100% **when the model is fooled**; jailbreak, excessive agency, data leakage, denial of wallet; a red-team plan before a major gate. | the surfaces list; the controls and how each is verified; the red-team plan |
| **E8 Regression and baselines** | What triggers a run besides a commit (a prompt edit, a tool description, a model version, the calendar); how baselines are kept (a named approver and a written reason; compare to current and previous). | the triggers; the baseline policy |
| **E9 Gates** | Per dimension and stage: an **absolute floor** and a **regression band**, never a bare threshold at current performance; zero-tolerance checks apart from rate-based ones; which dimensions have a **validated judge** (an unvalidated judge does not gate). | the gate table |
| **E10 Ownership** | The dataset owner (not the person who owns the prompts), the judge owner, the gate approver, the owner of the prohibited-operations list (someone with operational authority). | names or roles |

**R3 Ask; do not invent.** The skill never writes the answer key for domain facts. It asks the developer and the expert, shows each proposed case, and marks anything it drafted `proposed-by-assistant: needs expert review` until a named person has reviewed it. It does not create scenarios the person did not choose; where the guide's three missing classes are absent, it **asks for** examples. Synthetic cases come only after expert-authored seeds exist, are marked `source: synthetic`, and name the generating model (the guide's warning: a model-generated dataset inherits the system's blind spots).

**R4 Map onto the stack, honestly.** `references/eval4j-loom-mapping.md` lists, for every decision above, the construct that carries it today (for example `weave test` scripted models and fault injection; `weave eval` and eval4j for outcomes and judges; `weave audit`, `review` and the lethal-trifecta rule for static security; `weave capture`, `regress` and `gate` for the production loop; the dashboard's dimensions and By-agent view), the guide chapter that builds it, and **a gaps column** pointing at the [register](../FIELD_FINDINGS.md) (F55 to F65) with the workaround in the meantime (a tag convention for splits, a spreadsheet for kappa, a script for repetitions). **Every command and option the page names is checked by a test against the real CLI**, so the page cannot drift or promise what is not there. Each gap closed in Phase 19 removes a row.

**R5 The rules the skill enforces on itself and on the developer.** These come straight from the guide's Do's and Don'ts and from the field session:

1. Define what *correct* means before measuring quality. A property with no oracle is not tested.
2. Measure outcomes statistically; one run is not a result.
3. **Model-level defences are mitigations (percentages); system-level controls are controls (100%).** Never accept a percentage where a control is required; report which is which.
4. A mock run proves wiring, not quality; **it is never presented as an evaluation**.
5. A judge is an instrument: calibrated against humans before it gates, from a different model family than the agents, at temperature 0, one dimension per call, with its rubric in a versioned file.
6. Do not re-baseline to hide a regression; a new baseline needs a named approver and a reason.
7. Do not mistake consistency for correctness, or variability for failure.
8. Read **a real answer** under a cap before building a dataset around the workflow (F31).
9. Never describe a report that has not been read (F34).

**R6 Reading results honestly.** `references/judge-and-pipeline.md` states how a report is read, and the skill makes the developer apply it before drawing a conclusion: pass@1 **and** pass^N; the generalisation gap; slices (a cell with four cases is noise); the unmatched rate; the instrument's **resolution** (about 100 runs detect 20 points, about 400 detect 10, about 1,500 detect 5); the five-step test of whether a movement is real (outside the historical band, statistically significant, reproduces on re-run, correlates with a configuration change, otherwise suspect provider drift); and per-case stability (a case that passes 2 runs in 5 is telling you about the case).

**R7 Review mode.** Asked "is my evaluation any good" or pointed at an existing project, the skill runs a **review** instead of a design: it reads the dataset, the harness and a recent report, scores each of the guide's anti-patterns (generated by the same model family as the system; only cases the system passes today; no unanswerable cases; ground truth written without domain review; no metadata; dataset owned by the prompt engineer; never refreshed; unvalidated judge; thresholds set at current performance; mock results quoted as quality), and writes `EVAL_REVIEW.md`: findings, severity, the fix, and which stage of the design it belongs to. A finding it cannot confirm from the files is marked **not verified**, never assumed.

**R8 Where it sits, and the hand-overs.** In the journey the skill comes **after "decide the agents" and before "build the golden dataset"**: the design decides what the dataset must hold. It hands over to `llm4j-workflow-guide` (the dataset, prompt and agent tests, workflow and trajectory tests, the real evaluation), to `llm4j-harness-design` (bounds and loop induction, durability and the human gate: the design records point at each other), and to `llm4j-loom-deployer` (the drift check, observation of the live system, incident-to-case growth). `weave next` mentions the design when a project has agents and no `EVAL_DESIGN.md` ([Phase 20](../20-guided-path-and-skills/DESIGN.md) R-numbers).

**R9 Packaging and tests.** The skill is added to `weave guide --install-skill` (which then installs four), to `make skill-package` and `docs/PUBLISHING_SKILL.md`, to the root README's skills table, to `llms.txt`, and to the workflow skill's description as the evaluation companion. The existing skill tests (`SkillKitTest`, `HarnessSkillTest`, `AgentInstructionsTest`) are extended to cover it. See [TEST_STRATEGY.md](TEST_STRATEGY.md).

**R10 Attribution and licence (D11).** Each reference page that distils the guide says so, names it, and links external works the guide cites (Husain, Willison, OWASP Top 10 for LLM Applications, Zheng et al., and the tools). Nothing is copied at length: the pages are written in the stack's terms. The owner confirms the licence under which the guide's content may be redistributed with the repository (Apache 2.0) before anything ships.

## 5. The design record (`EVAL_DESIGN.md`)

The template has the guide's ten sections as headings (layer map, dimensions, oracles, dataset, run protocol, harness, security, regression, gates, ownership), each with the fields the stage above lists, a **decisions log** (what was decided, by whom, when, why), a **gaps and workarounds** table (the stack's missing capabilities this project depends on, each with its register id), and an **open risks** list (an unvalidated judge, a dataset below the minimum size, a missing held-out set, anything accepted with a named owner and an expiry date). A filled worked example, shaped like the `article-flow` project (a five-agent article workflow), ships as `references/design-record.md`'s appendix and is the skill's own test fixture.

## 6. Compatibility

A new skill; nothing existing changes except that installs list four skills and `weave next` may mention the design. Projects without `EVAL_DESIGN.md` are not blocked from anything.

## 7. Security notes

- The record contains **no secrets**, and the skill never asks for one; it names where keys live (the secret store) and never `.env.example` ([Phase 18](../18-install-and-identity/DESIGN.md) R8).
- The security corpus contains hostile text: the skill says where it lives, that it runs against sandboxed or fake tools, and that a successful attack becomes a permanent case.
- The skill tells the developer that selecting a secret in the editor shares it with the conversation ([Phase 20](../20-guided-path-and-skills/DESIGN.md) F33).

## 8. Open questions

1. D11: is the guide to be shipped as a document alongside the skill (for humans), or only distilled? The program page recommends distilling with credit; the owner decides.
2. Should the skill's stages be **executable** (a `weave eval design` command that walks the questions and writes the record), as `weave init` does for templates? Proposed: not now; the skill is the interface, and a command can follow once the record's shape has settled through use.
3. How much of the guide's Python tool landscape (DeepEval, RAGAS, Promptfoo, Langfuse, Phoenix) belongs in a Java-first skill? Proposed: one short page of "tools you may meet", marked as outside the stack, never a recommendation to replace eval4j.
4. The guide's targets (precision 0.75, recall 0.85, claim support 0.90, accuracy by domain) are **starting points**; the skill must say they are to be set by the domain expert per product, never adopted as a standard.

## 9. Documentation affected

The new skill; `docs/PUBLISHING_SKILL.md`; the root README skills table; `llms.txt`; the workflow skill's description and its evaluation stages (cross-link); `docs/guide/` (a pointer from chapters 2 and 8); the generated `weave guide` page; the extension's "Set Up My Coding Agent" text; `docs/specs/README.md`.
