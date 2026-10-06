# Design: Onboarding

## 1. Editor diagnostics (R1)

`vscode-loom/src/lsp/server.ts` has `parseDocument` with `VALID_CHAR_RE`, a re-implementation of the lexer. Replace
the diagnostics pass with a call to `weave check <file> --format json` (a new machine format, exit 0 with
diagnostics inside, as `weave graph` does), run through the same runner and debounce as the graph panel. The
scanner stays only for what needs no process: definitions, references and folding. Step one is the one-line fix.

## 2. `weave init` (R2)

Templates are directories in the jar (`/templates/<name>/…`) copied with name substitution. Each is exercised in CI:
`init`, then `check --no-env`, then `eval --mock`, so a template cannot rot. Java test scaffolding lives in a
separate template overlay so a script-only project never sees a pom.

## 3. Guide (R3, R8)

Chapters keep their order and gates. Each gains: a generic inline example (the refund bot, the newsletter, the ticket
classifier), the path fork (script-only versus Java), and the advice in R8. The Hexamind material moves to
`docs/guide/case-study-hexamind.md`. `weave guide` reads chapters from the jar; a docs test resolves every link.

## 4. Checks (R6, R7)

All in `ScriptValidator` and `AuditCommand`, reusing the graph builder where a structural question is easier on
the graph (R6.4 is a graph property: two edges from a human step reach one node with no condition on either).

## 5. Doctor (R5)

A shell script comparing `target/*.jar` and the local repository jar timestamps with `git log -1 --format=%ct` for
each module's `src`. Reports and prints the install command; changes nothing.

## 6. The skill (R9)

Planned changes to `SKILL.md`:

1. **Cold start first.** Add a section "Where you are working": check whether `examples/` and `docs/guide/` exist.
   If not, use `weave guide` and `weave init`; never ask the user to fetch the repository.
2. **Ask two things up front:** the stage (as now), and "Do you want tests first?" (see `loom-weave-eval` R4). Record
   the answer in the project README.
3. **Order of work** when tests are wanted: decide agents, golden dataset, script, `weave eval --check`, `--mock`,
   capped real run. If tests are skipped: script, `weave check`, `weave audit`, then a capped run.
4. **Start from a template** (`weave init`), not from an example. Hexamind is optional reading, not the model to copy.
5. **Two paths:** at stage 1, ask whether the workflow is script-only or embedded in a Java app; say which stages differ (after `loom-prompt-files`, none).
6. **Gate wording:** replace "prompt ids in a registry" with "prompts in `prompts/`", and the stage 2 gate with
   `weave eval --check` passing.
7. **Add pitfalls:** "Tests run: 0 is a failure", stale jars (`scripts/doctor.sh`), editor versus `weave check` disagreement (report it).
8. **Useful commands:** add `weave init`, `weave guide`, `weave eval`, `weave graph`, `--no-env`, `--prompt`.
9. **Remove** the final line that names `examples/hexamind-hub` as the worked example for every chapter.
10. **Never write a Java loader** for a script-only project; use `weave eval`.
11. **Honest edges:** keep the existing list and add what is still unsupported once the specs land.

## 7. The kit (R10)

The kit is three things: `weave.jar`, the extension, the skill. The jar carries `/guide/*.md`, `LOOM_GUIDE.md`,
`llms.txt` and `/templates`; `weave guide` and `weave init` read them. The extension bundles the same text and
offers "Install the Loom skill in this project", which writes `.claude/skills/llm4j-workflow-guide/SKILL.md` and
the guide next to it. The skill is rewritten so every path it names is one the kit has. The Java types the guide
uses are listed with coordinates and a pom snippet in the build chapter, because there is no source tree to read.
A script, `scripts/verify-kit.sh`, builds the kit and runs the empty-directory check in a directory with no
access to the repository.

## Open questions

1. `weave check --format json` shape: reuse the `diagnostics` array of `weave graph`? Proposed yes.
2. Template names: `pipeline`, `approval`, `classifier`. Others to add later?
