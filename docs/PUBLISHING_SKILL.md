# Publishing the llm4j workflow skill

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
