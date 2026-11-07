# Testing Strategy

The default build is fast and hermetic, and it is what a release is gated on. Everything slower, or dependent on the machine it runs on, is behind one profile.

## What runs when

| Command | What runs |
|---|---|
| `mvn -pl src/loom/ai-agent4j-loom -Pcore test` (`make test-core`) | **Core tier.** The Loom language core only (about 30 seconds): everything except the program-level packages (the weave commands, earned autonomy, policies, capture, time travel, the starters, rewind, bindings, the debugger, the server, the channels) and the `slow` tests. No coverage gates. |
| `mvn -Pfast verify` (`make test-fast`) | **Fast tier.** Every test except the ones tagged `slow`, plus the coverage rules (about 2 minutes for Loom). |
| `mvn -Pfull verify` (`make test-full`, `make test`, plain `mvn verify`) | **Full tier.** Every test that is not tagged `live`, `integration` or `fragile`, `slow` ones included, plus the coverage rules (about 5 to 6 minutes for Loom). No network, no database, no real model, no external program. |
| `mvn -Pextended verify` (`make test-extended`) | The default tests **and** the `integration` and `fragile` tests, plus the modules that are not published: `engram` and the example applications with their tests. |
| `mvn -Plive verify` (`make test-live`) | Only the `live` tests, which call real provider APIs with real keys and cost money. |
| `mvn -Prelease -Dgpg.skip=true verify` (`make release-check`) | The default build, plus the sources and javadoc jars a release uploads. |

## The tags

A test class (or method) is tagged with JUnit 5's `@Tag`:

- **`integration`**: needs something outside the JVM (a database, a local model server, an MCP server) or exercises several modules end to end. Tests named `*IntegrationTest` carry it.
- **`fragile`**: its result depends on something other than the code: wall-clock time (sleeping, polling a watcher or a fake server until a deadline), an external program (`git`, `javac`, `node`), the operating system's scheduler, or a timing threshold. They pass on a quiet machine and may not on a loaded CI runner.
- **`live`**: calls a real provider API.

The default build excludes all three. Untagged is the default for a new test: write it so it needs nothing from the machine, and tag it only when it really does.

- **`slow`**: a correct, hermetic test that takes more than about five seconds: a scale test, a property test over many random cases, or a test that replays everything in the repository. Unlike the three above it is **not** excluded by default; `-Pfast` leaves it out and `-Pfull` (the same as the default) runs it. Seven Loom tests carry it, and they are about 64% of the Loom test time. Tag a test `slow` only when it is correct and expensive, never to hide a performance problem in the code it tests.

To see what is tagged: `grep -rl '@Tag("fragile")' --include=*Test.java .`

## The tiers

The tiers exist so that the language can be checked often and the whole suite rarely. The loop is the maintainer's rule for any new build: core after every sub-phase, fast after every phase, and the full tier left to CI. The one `slow` test that runs in a session is the provenance oracle, alone, once at the end (`make test-oracle`): it runs every script in the repository and checks that no model-written value is treated as trusted. Any other slow test can be run alone by name when a change might affect it.

| Tier | Time (Loom) | When |
|---|---:|---|
| Core | about 30 s | **after every sub-phase** |
| Fast | about 2 minutes | **after every phase**, and before the final push |
| Oracle | about 16 s | **once per session, at the end**: `make test-oracle`, the provenance oracle alone |
| Full | about 5 to 6 minutes | **in CI on every pull request**; not part of the working loop, run by hand only when asked |

The coverage gates run in the fast and full tiers and not in the core tier, since a subset cannot meet them. Leaving the `slow` tests out of the fast tier was checked not to weaken the gates: with the seven heaviest classes excluded, every coverage rule was still met. The core tier is defined by excluding the program-level packages, so a new package is in it by default.

## Evaluating a workflow: three costs

For the workflows you build with Loom (not this repository's own tests), evaluation is run at three prices: `weave test` and `weave eval --mock` on every change (scripted, free); `weave eval --changed --max-tokens N` before a merge (real, only the cases of what changed, capped); and a full `weave eval` on a schedule (real, everything, off until someone turns it on). `weave init` writes the CI file. See the Loom guide, "Evaluating a Workflow".

## Deciding where a new test goes

1. Does it call a real API? `live`.
2. Does it need a database, a model server, or several modules wired for real? `integration`.
3. Does it sleep, poll with a deadline, run `git` or `javac` or `node`, depend on the OS, or assert how long something took? `fragile`. (Prefer a fake clock or an injected sleeper over a real wait; then it needs no tag.)
4. Otherwise it is an ordinary test and stays untagged.

## Coverage

The default build runs JaCoCo and writes `target/site/jacoco` for every module. `ai-agent4j-tools` and `ai-agent4j-loom` also enforce minimums on the classes where a regression would matter most; those rules run in the default build, so they must be met by the default tests alone.

## CI mapping

- **Pull requests and pushes:** `mvn verify` (the full tier: default tests, `slow` ones included, and coverage gates), and the release check (`-Prelease -Dgpg.skip=true`) so a broken javadoc or a missing field is found before a release, not during one.
- **Weekly, and on request:** `mvn -Pextended verify`, and the dependency vulnerability scan.
- **A version tag:** `mvn -Prelease deploy`, which runs the default tests again before anything is uploaded. See [PUBLISHING.md](PUBLISHING.md).
