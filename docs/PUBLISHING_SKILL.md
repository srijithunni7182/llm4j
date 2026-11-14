# Publishing the llm4j skills

There are six skills. They hand over to each other by name, in this order, and each can be used alone. A team can install only the deployer on a server.

| Order | Skill | What it does | Writes |
|---|---|---|---|
| 1 | **`llm4j-workflow-patterns`** | Picks the shape of the workflow from what the person wants to build, and starts from a tested skeleton | |
| 2 | **`llm4j-eval-design`** | Designs how the workflow will be evaluated: what correct means, the oracles, what the dataset must hold, how the judge is validated, the run protocol and the gates. Sits after "decide the agents" and before "build the golden dataset". Also reviews an existing evaluation | `EVAL_DESIGN.md`, or `EVAL_REVIEW.md` for a review |
| 3 | **`llm4j-harness-design`** | Designs the harness around an autonomous workflow: what agents may touch, how a run is bounded, how it fails, survives a crash and is stopped, who steps in | `HARNESS.md` |
| 4 | **`llm4j-workflow-guide`** | Builds, tests, secures and ships the workflow (chapters 1 to 16 of the guide), with a fast lane for a first version | |
| 5 | **`llm4j-run-debugging`** | Finds out why a run went wrong from its journal (`weave why`, `whatif`, `step`, `timeline`) and fixes it with a test | |
| 6 | **`llm4j-loom-deployer`** | Takes the finished workflow to a running, watched one: the security decisions, the rules file, a Fabric server, launching the application and the server with scripts, and reading the pages | |

`weave guide --install-skill` installs all six into a project. For one, add `--skill eval`, `--skill harness`, `--skill patterns`, `--skill workflow`, `--skill debug` or `--skill deployer`.

## The evaluation-design skill

`llm4j-eval-design` writes no Loom script and no dataset case, and needs no jar. It is a folder of text, so there is nothing to build:

```
llm4j-eval-design/
  SKILL.md                              the skill: nine rules, an eleven-stage path (E0 to E10), what is the user's to decide, review mode, the hand-overs
  references/principles.md              the four properties that change testing, the mindset shift, the do's and don'ts
  references/oracles.md                 the four oracle types, the decision procedure, metamorphic relations
  references/dataset-and-labelling.md   anatomy of a case, sources, coverage, the three missing classes, size, splits, labelling, governance
  references/quality-dimensions.md      the nine dimensions: metrics, targets, how each misleads
  references/security-testing.md        injection, agency, leakage, denial of wallet, red teaming, honest reading
  references/trajectory-and-multi-turn.md   trajectory, turn versus session, multi-turn cases, multi-agent and context tests
  references/judge-and-pipeline.md      the layers, the judge and how to validate it, run protocols, gates, reading results
  references/eval4j-loom-mapping.md     each decision, the construct that carries it today, and the gaps with their register ids
  references/failure-modes.md           the anti-patterns (the checklist of review mode) and the troubleshooting table
  references/design-record.md           the EVAL_DESIGN.md template and a worked example
```

**Build and install**

- `make skill-package` builds `dist/llm4j-eval-design.zip` from the weave jar and checks it from an empty folder.
- `weave guide --install-skill --skill eval` writes the same files into a project.

**Where the method comes from.** It is distilled from the owner's guide *Quality Assurance for AI agentic systems*, credited on every page that uses it. The guide's numbers are starting points the domain expert sets.

**What `EvalDesignSkillTest` checks**

- every `weave` command and option the pages name exists;
- every gap names a register id;
- the nine rules are present word for word;
- the dataset sample loads with the real loader.

**Keep it current.** Keep `references/eval4j-loom-mapping.md` in step with the CLI: when a gap in the register closes, its row goes.

## The harness-design skill

`llm4j-harness-design` writes no Loom script and needs no jar. It is a folder of text, so there is nothing to build:

```
llm4j-harness-design/
  SKILL.md                      the skill: ten rules, a ten-stage path with a gate for each, what is the user's to decide, the hand-over
  references/principles.md      the practices by area, each with why it matters and how to check it
  references/failure-modes.md   how harnesses go wrong: symptom, cause, fix
  references/loom-mapping.md    each decision, the Loom construct and guide chapter that carries it, and what Loom does not cover
  references/design-record.md   the HARNESS.md template filled in with the user
```

**Build and install**

- `make skill-package` builds `dist/llm4j-harness-design.zip` (a few KB) from the weave jar and checks it from an empty folder (complete, and pointing at no repository path).
- `weave guide --install-skill --skill harness` writes the same files into a project.
- To publish it, hand over the folder or the zip as it is.

**It refers to nothing outside itself.**

- It never sends the user to a repository path.
- When `weave` is installed it uses `weave guide loom` and `weave <command> --help` for the names of Loom constructs.
- Otherwise it designs anyway and leaves the mapping for the build.

**Hand-over.** Its last stage hands over to `llm4j-workflow-guide`, so install the two together. The deployer is the step after that.

Keep `references/loom-mapping.md` in step with the Loom reference when a construct changes: nothing checks it automatically.

## The workflow-patterns and run-debugging skills

Both are text only, like the harness-design skill, so there is nothing to build but a folder (or its zip):

```
llm4j-workflow-patterns/
  SKILL.md                      how to pick a shape with the person, and the hand-over to the workflow guide
  references/patterns.md        eleven patterns, each with a tested Loom skeleton, the questions to ask and the traps
llm4j-run-debugging/
  SKILL.md                      the loop: get the run, read it, name the kind of problem, try the change, test it
  references/triage.md          symptom, usual cause, first command
  references/commands.md        what each debugging command does and the options that matter
```

**What is tested**

- Every skeleton in `patterns.md` is a starter's `main.loom` (`src/loom/starters`), and a test compares them, so a skeleton cannot drift from a starter that is checked on every build.
- Another test checks that every `weave` command and option the two skills name exists.

**Build and install**

- `make skill-package` builds `dist/llm4j-workflow-patterns.zip` and `dist/llm4j-run-debugging.zip` and checks them from an empty folder.
- `weave guide --install-skill --skill patterns` and `--skill debug` write the same files into a project.

## The workflow skill

`SKILL.md` is the short form of the rules and says when to read each one; the full text is in `references/guiding-rules.md`, which travels with it.

`make skill-package` builds `dist/llm4j-workflow-guide.zip` (about 21 MB) and checks it from an empty folder. The package is self-contained, so it
can go to a skills marketplace or be handed to someone with nothing else installed:

```
llm4j-workflow-guide/
  SKILL.md              the skill; says where weave is
  references/           the guide's chapters, the Loom reference, the recipes, llms.txt
  bin/weave.jar         the runnable weave jar (the Loom `cli` build)
  scripts/weave         launcher (macOS, Linux): runs bin/weave.jar
  scripts/weave.cmd     launcher (Windows)
```

- **The only prerequisite** on the user's machine is Java 17 or newer.
- **One version.** `SKILL.md` and `references/` are taken from the jar itself (`weave guide --install-skill`), so the skill, the guide and the jar always describe the same version. Links that left the guide point at the repository as absolute GitHub links.
- **Verified from outside the repository.** `scripts/verify-skill-package.sh` unpacks the zip away from the repository and runs the bundled launcher: `--version`, `guide`, `init`, `check --no-env` and `eval --mock`. It fails if the skill refers to a repository path.

Rebuild the package for every release of Loom (the jar inside is the one that was just built). Marketplaces differ in how they take a skill;
most accept the folder or the zip as it is.

## The deployer skill

`make skill-package` also builds `dist/llm4j-loom-deployer.zip` (about 25 MB) and checks it from an empty folder: it brings up a real Fabric with its own scripts, runs a workflow that reports to it, and stops both.

```
llm4j-loom-deployer/
  SKILL.md              the skill
  references/           the eight chapters (weave guide d1 ... d8) and the map (weave guide deploy)
  scripts/              fabric-up, fabric-status, fabric-down, run-observed (.sh for macOS and Linux, .ps1 for Windows)
  bin/weave.jar         the runnable weave jar
  bin/loom-inspect.jar, bin/loom-inspect-core.jar   the add-on that serves the page and reports to Fabric
  bin/fabric.jar        the Fabric server
```

**What the scripts need**

- They find the jars in `bin/`, or in `FABRIC_JAR`, `WEAVE_JAR`, `INSPECT_JAR` and `INSPECT_CORE_JAR`.
- Java 17 or newer and, on macOS and Linux, `curl`.

**What the scripts do**

- They never take a secret on a command line or print one.
- They create a data directory and admin password the first time (from the environment, or a generated one written to a private file).
- They refuse to listen beyond this computer without TLS or a proxy.
- They are safe to run twice.

**Windows.** The `.ps1` versions are written to the same rules but are not run in the build (no Windows host). Check them on Windows before you rely on them.

`weave guide --install-skill` installs the skill's `SKILL.md`, chapters and scripts from the weave jar alone; the jars they launch are not inside weave's jar, so a project that wants the scripts to work unpacks the package or sets the four variables.
