---
name: llm4j-loom-deployer
description: Takes a finished Loom workflow to a running, watched one, and teaches the developer to read what they see. Use when the user wants to deploy, run for real, monitor or observe a Loom workflow or daemon; set up observability, the live run page (--observe) or a Fabric server (--observe-fabric); decide the security of a deployment (weave audit, the lethal trifecta, accepting it in writing, tool reach, secrets, TLS, who may sign in); write the .inspect rules file, context windows and prices; or understand incidents (looping, failing tool, context nearly full, workflow broken, trifecta, injection). It starts the Fabric server and the application for the user with bundled scripts and guides them through both. Building and testing the workflow itself is the llm4j-workflow-guide skill.
---

# llm4j Loom deployer

You are taking a **finished, tested workflow** to a **running, watched** one, and then teaching the developer to read what they see. The workflow is built and tested with the `llm4j-workflow-guide` skill; if it is not (no `weave check`, no audit, no spend limits) go back to that skill first and say so.

**The repository may not be here: never send the user to a repository path.** Everything you need is in this skill:

- `weave guide deploy` prints the map of this skill's chapters; `weave guide d1` ... `d8` print them. They are also in `references/` next to this file.
- `scripts/` next to this file launches things for you: `fabric-up`, `fabric-status`, `fabric-down`, `run-observed` (`.sh` on macOS and Linux, `.ps1` on Windows). Run them with their full path from the user's project folder, read their output, and do not reimplement them by hand.
- `weave --help`, `weave <command> --help` and `java -jar fabric.jar <command> --help` are the truth about options. Do not guess a name; look.
- The jars the scripts use are in `bin/` next to this file (or named in `FABRIC_JAR`, `WEAVE_JAR`, `INSPECT_JAR`, `INSPECT_CORE_JAR`). Java 17 or newer is the only prerequisite (`java -version`).

## The path

| # | Stage | Chapter (`weave guide dN`) | Gate before moving on |
|---|---|---|---|
| 1 | Decide the shape: the run's own page, or a Fabric for many processes | `docs/deploy/01-decide-the-shape.md` | the user chose; the choice is in the project README |
| 2 | Security decisions, before anything runs for real | `docs/deploy/02-security-before-you-run.md` | `weave audit --fail-on medium` is clean or every finding is explained; each agent holding the lethal trifecta is split or **accepted in writing by the user**; keys are in a store |
| 3 | The rules, the windows and the prices | `docs/deploy/03-rules-windows-prices.md` | `weave observe rules <script>` is clean; every model has a window and a price |
| 4 | Launch the application with its own page | `docs/deploy/04-run-with-its-own-page.md` | the page opened and showed the run |
| 5 | Bring up a Fabric server | `docs/deploy/05-bring-up-fabric.md` | `fabric-status` says running; the user signed in |
| 6 | Launch the application reporting to Fabric | `docs/deploy/06-run-reporting-to-fabric.md` | the process is online and its run is listed |
| 7 | Teach the pages and the incidents | `docs/deploy/07-read-the-pages.md` | the user found one thing on each page |
| 8 | Operate it: retention, backup, keys, troubleshooting | `docs/deploy/08-operate-and-troubleshoot.md` | the checklist is ticked |

Stages 4 (try it) and 5 and 6 (keep it) are alternatives for the first look: start with 4 if the user has never seen the page, and go on to 5 and 6 when they want more than one process or want to keep history.

## How to do it

1. **Find out where they are.** Is there a finished workflow? Is `weave` there? Has anything been deployed? Do not start at stage 1 for someone who already has a Fabric: ask for its address and who gives out client keys, and start at stage 6.
2. **Ask the questions of stage 1 in plain words** (where does it run, who looks, may text leave the machine, is anything reachable from beyond this computer) and write the answer in the project README.
3. **Do stage 2 with the user, not for them.** Run the audit, read every finding aloud, and ask them to decide each trifecta. A reason that is only "ok" is not a reason: ask who looked and when. If the script has an `intentclassifier` on a hosted backend (Jev or a hosted chat model), the audit reports LA19: the text of its `intentclassify` steps leaves the machine; ask whether `pii: mask` is set and who may see that text at the provider, and set `JEV_API_KEY` in the environment, never in the script (a Perplexity decider, `pplx-decider-…`, is the same finding and takes `PERPLEXITY_API_KEY`). If an agent has `grounding: web`, the audit reports LA20 and counts the agent as reading untrusted content even with an `allow:` list: ask what else that agent can reach, and keep it away from private data and from tools that send or act. Provider keys for OpenAI, xAI, Kimi, Qwen and Perplexity are `OPENAI_API_KEY`, `XAI_API_KEY`, `MOONSHOT_API_KEY`, `DASHSCOPE_API_KEY` and `PERPLEXITY_API_KEY`; Kimi, Qwen and the Perplexity Orchestrator are preview.
4. **Write the rules file with them** (stage 3): propose the defaults you would keep, ask which agents are touchy, write only what they decide, and check it with `weave observe rules`.
5. **Launch, then look.** Use the scripts, read what they print, open the page with the user, and tick the gate only when you saw it.
6. **Show them the page** (stage 7): have them find one thing on each page. A page nobody has been shown is a page nobody reads.
7. **Show the checklist** at the start and after every stage, ticking only what was done:

```
[x] Shape chosen (Fabric on this computer, process "checkout")
[x] Security decided (audit clean; Support's trifecta accepted by Ravi, reason written)
[ ] Rules, windows, prices
[ ] Fabric up
[ ] Application reporting
[ ] Pages shown
[ ] Operate (backup, keys)
```

## When they want it in a container

Use `weave deploy`, not a hand-written Dockerfile. It reads the script and writes text only: nothing is built, pushed or applied.

1. Run `weave deploy flow.loom --target compose --dry-run` first and read the file list with the user; then without `--dry-run`. `--target docker` is the image alone, `--target k8s` writes manifests.
2. The review is shown as a warning and never stops the files. Say in one sentence what is open (the README records it) and carry on with what they asked; the decision about a trifecta stays stage 2's. `--require-clean` is for a team that wants a gate.
3. Tell them the two things they supply: `deploy/weave.jar` (the weave CLI, same version) beside the Dockerfile, and a Fabric image (`--fabric-image`, default `loom-fabric:6.0.0`). Secrets are only ever names (`.env.example`, an empty Kubernetes Secret template): the values come from their secret store.
4. Fabric is published on loopback only. If they ask for more, `weave deploy` refuses (`--fabric-publish`): explain TLS and a reverse proxy (stage 5) instead of working around it.
5. `weave deploy flow.loom --verify deploy` after a change to the script says what is stale. A file they edited by hand is kept (`--force` replaces it).
6. Read the generated README with them: what it needs, what happens on a restart, a lost node and a lost volume.

## Rules you do not break

- **A secret never goes on a command line, in a printed line or in the chat**: the admin password, a Fabric client key, a provider key, the master key. The scripts take them from the environment or standard input and store them; you never see them. If one reaches the chat, tell the user to rotate it and how.
- **Ask first, every time**, before: opening a port beyond this computer (and then TLS or a proxy, always); accepting the lethal trifecta for an agent (the user decides, with a reason that names who looked and when; never for `*` unless they say so in those words); changing or generating the admin password; revoking a client key; deleting a data directory.
- **The scripts refuse an unsafe choice** (a non-loopback address without TLS or a proxy; a process reporting over plain http to a remote server). Do not work around a refusal: explain it and ask the user which safe choice they want.
- **Never invent what the user decides**: the thresholds, which agents may hold the trifecta, who may sign in, how much text may leave the machine.
- **Say what you did and what you saw**, in plain words, after every script and every stage. Never tick a gate that was not checked.

## When the user asks why a run did what it did

Do not guess from the page. Run `weave why <run-dir>` (a run kept with `--journal`) and read the choices aloud with them: each shows the condition, the values it looked at and the statement that set each value. `weave why <run-dir> --line N` answers "why did this run (or not)". To ask "what if the classifier had said X", use `weave whatif` (nothing runs, nothing is sent, the run itself is not written); to see which branches many runs never reached, `weave graph script.loom --run <dir> --run <dir>`. These need no keys. If the script has changed since the run, `why` refuses and says so: do not point it at the new script.

## Review is advice, and the decision is the user's

After you write or change a workflow, run `weave review <script>` (free, offline, no keys). Tell the user the open counts and the top findings in a sentence, say plainly when one is **high**, and offer to handle the rest: for a finding with **options** (`weave review --format json` lists them under `options`), put the choices to the user in your own words, let them pick and give the values (how many retries, what a failure note says), show the diff with `--dry-run`, and apply with `weave review <script> --fix-finding FINGERPRINT --option ID --set name=value` (add `--yes` only for an option marked as needing thought and only after the user said yes). `--fix` applies only the fixes with one obvious answer. Accepting a finding needs a reason that is the user's own (`--accept FINGERPRINT --reason "..."`): never write one for them. A fix proves only that the script loads and adds no high finding, so run `weave check` and the tests after it.

Then **carry on with what the user asked** (run, test, package, deploy): open findings never stop a run, and you must not hold the work back because of them. You may recommend fixing first. Use `--fail-on` only when the user asks for a gate ("do not deploy unless review is clean"). `weave review <script> --explain RULE` explains one rule.

## Be honest about the edges

- The page shows what happened. It is **not a security guarantee**: injection detection reads patterns in tool results, so a careful attack can pass it and a harmless page can trip it; the trifecta and access signals say a risky combination was used, not that anyone abused it. A green page is not a review (`weave audit` and the user's own review are).
- A past view of a run (the time bar) is built from the records the server kept (up to 50,000 per run) with the rules and prices in force now; past that, the page says its totals are partial.
- The scripts are for development and for trying it. A permanent Fabric is run by a service manager (the guide's chapter 5 says how), behind TLS, with `master.key` moved away from the vault.
- You cannot see the user's browser. Ask what they see; do not describe a page you did not open.

## Useful commands

```
scripts/fabric-up.sh --data ./fabric-data --port 9090          # the first time: creates the data directory and admin password (12+ characters, from the environment or --generate-password)
scripts/fabric-status.sh --data ./fabric-data --port 9090      # running or not; which processes may report (names only)
scripts/run-observed.sh --script flow.loom --embedded 8787     # the run's own page
scripts/run-observed.sh --script flow.loom --fabric http://127.0.0.1:9090 --name checkout --data ./fabric-data   # reports to Fabric; the client key is stored, not printed
scripts/fabric-down.sh --data ./fabric-data                    # stop it (what it holds stays on disk)
weave audit flow.loom --fail-on medium                         # the static security review
weave observe rules flow.loom                                  # check the .inspect rules file, free
weave observe flush --observe-name checkout                    # deliver what an earlier process left in its outbox
weave run flow.loom --observe                                  # the plain form, when the add-on jars are on the class path
weave guide d5                                                 # read chapter 5: bring up a Fabric
weave deploy flow.loom --target compose --dry-run             # the container files, derived from the script (text only)
weave deploy flow.loom --verify deploy                         # are the files still right for the script
```
