# Phase 18 design: install, identity and environment (the first ten minutes)

Status: **draft for review**. Layer: Loom CLI (`ai-agent4j-loom`: `--version`, `doctor`, `init`, `check`, `guide`), the skills (`.claude/skills/*`), the VS Code extension, the build (`pom.xml`, release scripts), the model and price catalogue (`ai-agent4j`).
Part of the [field findings program](../FIELD_FINDINGS.md); read its §2 and §5 (decisions D5, D6) first. Documents: [TEST_STRATEGY.md](TEST_STRATEGY.md), [VERIFICATION.md](VERIFICATION.md). Builds on [Phase 16](../16-first-run/DESIGN.md) (`weave doctor`, the launcher, the JBang catalogue).
Findings owned: **F10 to F19**.

## 1. Problem

Phase 16 made the first run work for someone who follows the documented path on a clean machine. The field session was not on a clean machine: it had an older `ai-agent4j-loom` 6.0.0 in `~/.m2`, a newer `weave.jar` inside the editor extension (also "6.0.0"), skills installed
without the jars their scripts need, a coding agent in a terminal with no `weave` command, a language server that left half-compiled classes in `target/`, a template model that the account could not use, and keys typed into a file meant to be committed. Each cost the developer
time they could not have predicted, and none of them told the developer what was wrong. Two of them (the two jars with one name, the model that 404s) made a result *look* different from what it was.

The theme: **a developer should be able to tell, in one command, which tool they are really running and whether the pieces around it agree.** And a tool should never leave a person to discover a mismatch by reading source.

## 2. Goals and non-goals

Goals:

- Every build says exactly what it is: a release, or a development build, from which commit, built when (R1).
- `weave doctor` finds two jars that disagree and says which to remove, and a Fabric or inspect add-on that does not match its core refuses to start with one sentence (R2).
- The skills install **complete**: whatever a skill's scripts need is found, fetched with a checksum on request, or named with the one command that gets it (R3).
- A coding agent in any terminal can run `weave` (R4); the editor offers to set up the project's coding agent once (R5).
- A broken build that packages half-compiled classes is caught by the build, and the cause is documented (R6).
- Model defaults are current, a model that the key cannot use is found before a run, and windows and prices exist for the providers the templates use (R7).
- A secret in a file meant to be committed is noticed, and the safe route (the secret store) is the one the tool shows first (R8).

Non-goals:

- Not a package manager. Homebrew, SDKMAN and Scoop stay follow-ups, as in Phase 16.
- Not automatic downloads. Anything fetched is asked for, checksummed and named.
- Not a change to the Maven Central release flow beyond what R1 needs (the snapshot policy).
- Not the skills' process (roadmap, preflight, dataset co-writing): that is Phase 20.

## 3. Requirements

### 3.1 Build identity (F10, F11, F12; decision D5)

**R1.1** `weave --version` prints: the version; **`release` or `development`**; the git commit (short) and whether the tree was dirty; the build time (UTC); the serve protocol. Example:
`weave 6.2.0-SNAPSHOT (development, 3d683a35, built 2026-10-29T15:30:00Z), serve protocol 1`. A release build says `weave 6.1.0 (release, db5b43c7, built …)`.
**R1.2** The same facts are in the jar manifest (`Implementation-Version`, `Build-Commit`, `Build-Time`, `Build-Kind`) of **every** module jar, so any tool can read them without running Java code.
**R1.3 Development versions (D5).** Between releases `main` carries `X.Y.(Z+1)-SNAPSHOT`, not the last release's number. The release procedure ([PUBLISHING.md](../../PUBLISHING.md)) sets the release version, tags, then sets the next snapshot in the same flow. Consequences to design in 18a:
(a) `LauncherTest.theJbangAliasPointsAtTheCliJarOfThisVersion` currently requires the catalogue to equal the pom version; with snapshots it must equal the **last released** version, read from one source of truth (a property in the parent pom, `llm4j.released`, updated by the release step), and still fail if they differ;
(b) the extension's `build-vsix.sh` bundles a jar built from `main`, so the bundled engine reports `development`, with its commit; `weave doctor` and the extension's status text show it;
(c) docs that name coordinates keep naming the **released** version (a docs test already ties them to `VERSION_MATRIX.md`).
**R1.4** A bug report written from `weave --version` is enough to find the commit. The issue template asks for it.

### 3.2 Doctor sees disagreeing jars; add-ons refuse a mismatched core (F11)

**R2.1** `weave doctor` gains a check **"jars"**: it lists every `ai-agent4j-loom` and add-on jar it can reach (the running jar; jars on `WEAVE_CLASSPATH`/`--classes`; `~/.m2` copies of this project's version and the newest; a `weave` found on `PATH`; the extension's bundled jar when
`~/.vscode*/extensions/srijithunni7182.vscode-llm4j-loom-*` exists) with their identity from R1.2, and reports **any two that differ in commit under the same version** as one sentence with the fix ("`~/.m2/…/ai-agent4j-loom-6.0.0.jar` (release, Oct 17) and the weave you are running (development, Oct 29) both say 6.0.0. Remove the older copy, or run with `-Dloom.jar=…`").
**R2.2** `loom-inspect`, `loom-inspect-core` and `loom-fabric` check, at start-up, that the core jar they load has the same `Build-Commit` family (same version, and for development builds the same commit), and refuse with one sentence naming both when it does not. A missing class at run time (the field failure) becomes this sentence.
**R2.3** Both checks print nothing when everything agrees and never send anything anywhere (local files only), as Phase 16 requires of `doctor`.

### 3.3 The skills install complete (F13; decision D6)

**R3.1 One lookup for the tools a skill needs.** The deployer skill's scripts (`lib.sh`, `lib.ps1`) find `weave`, the Fabric jar and the inspect add-on in this order: the environment variable (`WEAVE_JAR`, `FABRIC_JAR`, `INSPECT_JARS`); a `weave` on `PATH` (and its sibling jars when it is a launcher); the editor extension's jar (for `weave` only); the skill's own `bin/`; then a **named, asked-for** fetch (R3.3). The order is the same in the shell and PowerShell versions and is tested for both.
**R3.2 No silent holes.** `weave guide --install-skill` ends with a short **"what is here and what is missing"** table: `weave` (found at …, build id …), Fabric (found / missing: run `weave tools fetch fabric`), inspect add-on (found / missing: …). It never claims a skill is ready when a script it ships cannot run.
**R3.3 `weave tools fetch <fabric|inspect|all>`.** Downloads the jar of **the version this weave was released as** from Maven Central into `~/.llm4j/tools/<version>/`, after listing the URL and size and asking (`--yes` to skip), checks the published SHA-256 (or SHA-1 where Central offers only that, saying so), refuses a development build ("this is a development build; build the module or install a release"), and prints the path and the environment variable to set. No other network access; the hosts are listed first, as `doctor --network` does.
**R3.4** The skills' own text stops saying "put the jar in `bin/`" as the only route and names R3.1's order.

### 3.4 `weave` for a coding agent in a plain terminal (F14)

**R4.1** The skills ship a small launcher (`scripts/weave` for POSIX, `scripts/weave.cmd`) that finds a weave by R3.1's order (not itself) and runs it, or prints the one-line install (`jbang app install weave@srijithunni7182/llm4j`) and the extension route. A coding agent is told in the skill text to call `scripts/weave` when `weave` is not on `PATH`.
**R4.2** `weave doctor` says, in a plain terminal with no `weave`, what the extension's terminal integration covers (VS Code's integrated terminals) and what it does not, and gives the install line.
**R4.3** The extension README and the developer guide state the same boundary (they already say that outside VS Code the install is needed; R4 makes the tool say it too).

### 3.5 The editor offers to set up the coding agent (F15)

**R5.1** When a folder with a `.loom` file is opened and no coding-agent instruction file written by **Loom: Set Up My Coding Agent** exists, the extension shows **one non-modal notification** ("Set up your coding agent to build with Loom?" with *Set up*, *Not now*, *Don't ask again*). The choice is remembered per workspace and globally for *Don't ask again*. It never writes a file without the person choosing, and shows the list of files first (the command already does).
**R5.2** The welcome view's button for the same command stays as it is.

### 3.6 No half-compiled classes in a jar (F16)

**R6.1 The build refuses them.** The release check (`make release-check`, and the CI job `Release check`) scans every module jar it builds for the compiler-stub marker (`Unresolved compilation problem`) and fails, naming the class. A plain `mvn package` over stale editor output is the case.
**R6.2** `CONTRIBUTING.md` and the contributor `scripts/doctor.sh` explain the cause (the editor's Java language server writes compiled output into `target/classes`), say to use `mvn clean package` for anything that will be shipped or run, and to set the server's build output elsewhere or turn its auto-build off. `doctor.sh` warns when a `target/classes` file holds the marker.
**R6.3** The same marker in the **extension's bundled jar** fails `scripts/verify-vsix.sh`.

### 3.7 Model defaults, availability, windows and prices (F17, F18)

**R7.1 A dated catalogue.** The shipped catalogue lists, per provider, the models the templates and examples use, with **context window, output limit and price (input, output, search)** and an **as-of date**, for Gemini, Anthropic, OpenAI, xAI, Kimi, Qwen and Perplexity. It is data (a resource file), not code. `weave doctor` and `weave check` say when the catalogue is older than 90 days.
**R7.2 Current defaults.** `weave init` and `weave generate` pick a default model **per provider the developer has a key for**, from the catalogue's "recommended" flag (a cheap model and a strong model), instead of a fixed name. A template that names a model names it as a `{{model}}` placeholder resolved at `init` time, with the choice printed.
**R7.3 `weave check --online`** (opt-in; the only network the check ever uses): for each provider the script uses and has a key for, one free "list models" call, and reports a model name the key cannot use with the available alternatives ("`gemini-2.5-flash-lite` is not available to this key; available: gemini-3.5-flash-lite, gemini-3.8-flash"). Keys are resolved through the secret store as everywhere; no key or model list is logged.
**R7.4 `weave prices update`** (opt-in): fetches the maintainers' current catalogue file from the repository (the host is listed, the checksum verified), writes it to the user's config folder, and `weave` prefers it to the shipped one when newer. Offline, the shipped one is used and its age is shown.
**R7.5** A run's cost line says **where the price came from** (catalogue, as-of date, or a price file the user gave) beside the cost, so a stale price is visible (links to Phase 17 R6's measured/estimated labels).

### 3.8 Secrets go in the store (F19)

**R8.1 `weave check` and `weave doctor` warn** when a file named `.env.example`, `*.example`, `*.sample` or `*.template` in the project holds a **non-empty value for a name that looks like a secret** (ends in `KEY`, `TOKEN`, `SECRET`, `PASSWORD`, or is a known provider variable): "`.env.example` has a value for `GEMINI_API_KEY`. It is meant to be committed. Move it with `weave secrets import-env .env.example` and blank the file." The warning **never prints the value**, nor a prefix of it.
**R8.2 `weave init` prints the secret-store steps first**: `weave secrets create`, `weave secrets set <NAME>` (or `import-env`), then `--secrets <file>`; the `.env` route is shown as the second option, with the sentence "never in `.env.example`". It generates `.env.example` with names and **empty** values and a `.gitignore` that lists `.env`, the secret store and key files.
**R8.3** `weave secrets import-env <file>` accepts any file (it does for `.env`); when the file is a `*.example`, it offers to blank it afterwards. (Today the reporter moved the values and blanked the file by hand.)
**R8.4** The skill-side guidance (ask first whether the person will run it themselves or deploy it, offer both routes together) is Phase 20 (F32).

## 4. Compatibility

| Change | Existing users |
|---|---|
| R1 | Output of `weave --version` changes; scripts that parse it ([the extension already reads "serve protocol N"](../16-first-run/DESIGN.md)) keep working because that suffix is kept. Snapshot versions change how the repository is released (D5). |
| R2, R6 | New warnings and a stricter build. No runtime change. |
| R3, R4 | New commands and a launcher; the old `bin/` route still works. |
| R7.2 | A template's model name becomes the chosen default. Existing projects are untouched. |
| R8 | New warnings; `init` output changes. |

`docs/API_COMPATIBILITY.md` records R1.2 (manifest attributes) and R7.1 (the catalogue file format) as public contracts.

## 5. Security notes

- R3.3 downloads code: it asks, shows the URL, checks a checksum, refuses a development build, and writes only under `~/.llm4j/tools`. It never executes what it downloads.
- R8.1 handles secrets: a test proves no value, prefix or length reaches stdout, stderr, a log or an exception message.
- R7.3 and R7.4 are the only network uses added; both are opt-in and list their hosts first.

## 6. Open questions

1. R1.3: do development builds in the extension's bundled jar show up in the Marketplace page ("Engine: development build 3d683a35")? Proposed: yes, in the status bar text, not on the page.
2. R3.3: should `tools fetch` also offer the CLI jar itself, for people without JBang? Proposed: no; the release page and JBang already cover it (Phase 16).
3. R7.4: where does the maintained catalogue live (the repository's `main`, a release asset, a separate repo)? It affects the checksum story.
4. R5.1: is a notification acceptable at every folder open until dismissed, or only once per machine?

## 7. Documentation affected

`PUBLISHING.md` (snapshot flow), `CONTRIBUTING.md`, `install/README.md`, `docs/GETTING_STARTED.md`, the generated `weave man doctor/check/init/guide/tools`, the deployer and workflow skills, the extension README and CHANGELOG, `docs/API_COMPATIBILITY.md`, `docs/security/secure-by-construction.md` (R3.3, R8.1), `llms.txt`, the issue template.
