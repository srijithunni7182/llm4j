# Requirements: A First-Time User's Path (Onboarding)

## Introduction

A cold-start dogfooding session (see `findings.md`) showed that Loom's hard parts work on the first try and that
the time goes to scaffolding, example drift, tooling that disagrees with itself, and environment traps. This spec covers
what is not already in `loom-prompt-files` and `loom-weave-eval`.

## Requirements

### Requirement 1: The editor and the compiler agree (F1)

1. The extension's diagnostics come from the same parser as `weave check`. The hand-written TypeScript scanner is not
   used to decide what is an error.
2. A script that `weave check` accepts shows no syntax error in the editor, and one that it rejects shows the same message at the same line.
3. Until that lands, `%` is accepted by the scanner (a one-line fix, with a test that uses `warn_at: 80%`).
4. Diagnostics refresh within 500 ms of a pause in typing when `weave check` is fast enough; otherwise they refresh on save and say so.
5. A regression test runs every `.loom` in the repository through both paths and requires identical results.

### Requirement 2: A starter project (F4, F5)

1. `weave init <template> [dir]` creates a small, complete, script-only project: `main.loom`, `prompts/`,
   `eval/golden/` with one starter scenario per agent, a `README.md`, and no Java.
2. At least three templates of different shapes: **pipeline** (agents in sequence with a bounded review loop),
   **approval** (a human approves a risky step; spend cap and guard shown), **classifier** (one agent with a golden
   dataset and a report).
3. `weave init --with-java-tests` adds a ready Maven test module (a pom with a surefire version that runs JUnit 5, a
   dataset loader built on `EvalScenarios.fromDirectory`, a scripted wiring test) for users who want JUnit.
4. Every template passes `weave check` with `--no-env` and `weave eval --mock` out of the box, and the project says
   how to add keys.
5. A template never contains a key, and `weave init` refuses to overwrite files.

### Requirement 3: Self-contained guide (F4)

1. Each chapter of `docs/guide/` carries a small generic example inline. Hexamind moves to a labelled "advanced case
   study" chapter and is linked by a stable URL, not a repository-relative path.
2. The guide ships with the tools: `weave guide [chapter]` prints a chapter, and the extension and `weave.jar` carry the same text.
3. The guide says the two paths (script-only, Java-embedded) and says which stage differs, in one place (see `loom-prompt-files` R7.3).
4. A test fails when a chapter links to a repository path that does not exist in the shipped docs.

### Requirement 4: Java test projects run their tests (F6)

1. A guide chapter and the `--with-java-tests` template say: "Tests run: 0" is a failure, and name the surefire version needed.
2. The template's pom fails the build when no test ran (surefire `failIfNoTests`, or an equivalent check).

### Requirement 5: Stale builds are noticed (F7)

1. `scripts/doctor.sh` (and `make doctor`) compares each module's installed jar with its sources and says which module to reinstall and the command.
2. The build guide says: after pulling, run the install command; a missing-package compile error in a dependent module means a stale jar.

### Requirement 6: `weave check` catches what it can (F8, F10)

1. A variable that is set and never read, in any workflow, is a warning with the line ("`override` is never used").
2. A `human_prompt` whose answer is never read is a warning that says so in plain words.
3. A missing API key is reported as `not set yet`, not as a problem, when `--no-env` is given; without the flag it
   still fails, with the message "set it, or use `--no-env` to check the script only".
4. A workflow in which a declined human decision leads to the same next step as an accepted one is a warning.
   (The check is structural: both outcomes reach the same node with no condition between them.)
5. `weave check --strict` turns these warnings into errors, so CI can require them.

### Requirement 7: The audit knows a person acts on the output (F9)

1. When a workflow's final output is a program or script (an agent whose output is declared as code, or a persona that
   says it writes scripts) and its inputs include untyped text from a tool that reads untrusted content, the audit
   reports an **info** finding: "A person will run this output; it was written from untrusted text."
2. The finding says how to reduce the risk: type the hand-off, review the output with a different model, keep the
   author without tools.
3. The finding is info, never medium or high, and is not raised for workflows with no untrusted reader.

### Requirement 8: Guidance the guide gives (F11)

1. The guide's chapters on building and on validating advise using a different model for a reviewer than for the
   author of the thing it reviews, and say why.
2. They advise bounding clarification rounds, and show the `loop … max` form for it.
3. They include the "declined approval" scenario as the example of a workflow scenario in `weave eval`.

### Requirement 9: The skill

1. `.claude/skills/llm4j-workflow-guide/SKILL.md` is updated as specified in `design.md` (cold start, templates first,
   evaluation optional, the order of work, no reliance on `examples/`).
2. A test checks that every command in the skill exists in `weave --help`, and every chapter it names exists.
3. The skill's description and body are checked with a cold-start session (the exercise prompt in `findings.md`'s source) before release.
