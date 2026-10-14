# Publishing the llm4j skills

There are two skills. **`llm4j-workflow-guide`** builds, tests, secures and ships a workflow (chapters 1 to 14 of the guide). **`llm4j-loom-deployer`** takes the finished workflow to a running, watched one: the security decisions, the rules file, a Fabric server, launching the application and the server with scripts, and reading the pages. They hand over to each other by name; a team can install only the second on a server. `weave guide --install-skill` installs both into a project (`--skill workflow` or `--skill deployer` for one).

## The workflow skill

`make skill-package` builds `dist/llm4j-workflow-guide.zip` (about 21 MB) and checks it from an empty folder. The package is self-contained, so it
can go to a skills marketplace or be handed to someone with nothing else installed:

```
llm4j-workflow-guide/
  SKILL.md              the skill; says where weave is
  references/           the ten chapters, the Loom reference, the recipes, llms.txt
  bin/weave.jar         the runnable weave jar (the Loom `cli` build)
  scripts/weave         launcher (macOS, Linux): runs bin/weave.jar
  scripts/weave.cmd     launcher (Windows)
```

The only prerequisite on the user's machine is Java 17 or newer. `SKILL.md` and `references/` are taken from the jar itself
(`weave guide --install-skill`), so the skill, the guide and the jar always describe the same version; links that left the guide point at the
repository as absolute GitHub links. `scripts/verify-skill-package.sh` unpacks the zip away from the repository and runs the bundled
launcher: `--version`, `guide`, `init`, `check --no-env` and `eval --mock`; it fails if the skill refers to a repository path.

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

The scripts find the jars in `bin/` (or in `FABRIC_JAR`, `WEAVE_JAR`, `INSPECT_JAR` and `INSPECT_CORE_JAR`), need Java 17 or newer and, on macOS and Linux, `curl`. They never take a secret on a command line or print one, create a data directory and admin password the first time (from the environment, or a generated one written to a private file), refuse to listen beyond this computer without TLS or a proxy, and are safe to run twice. The `.ps1` versions are written to the same rules but are not run in the build (no Windows host): check them on Windows before you rely on them.

`weave guide --install-skill` installs the skill's `SKILL.md`, chapters and scripts from the weave jar alone; the jars they launch are not inside weave's jar, so a project that wants the scripts to work unpacks the package or sets the four variables.
