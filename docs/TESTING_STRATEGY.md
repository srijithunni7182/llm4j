# Testing Strategy

The default build is fast and hermetic, and it is what a release is gated on. Everything slower, or dependent on the machine it runs on, is behind one profile.

## What runs when

| Command | What runs |
|---|---|
| `mvn verify` (`make test`) | Every test that is not tagged below, plus the coverage rules. No network, no database, no real model, no external program. |
| `mvn -Pextended verify` (`make test-extended`) | The default tests **and** the `integration` and `fragile` tests, plus the modules that are not published: `engram`, `tantrik` and the example applications with their tests. |
| `mvn -Plive verify` (`make test-live`) | Only the `live` tests, which call real provider APIs with real keys and cost money. |
| `mvn -Prelease -Dgpg.skip=true verify` (`make release-check`) | The default build, plus the sources and javadoc jars a release uploads. |

## The tags

A test class (or method) is tagged with JUnit 5's `@Tag`:

- **`integration`**: needs something outside the JVM (a database, a local model server, an MCP server) or exercises several modules end to end. Tests named `*IntegrationTest` carry it.
- **`fragile`**: its result depends on something other than the code: wall-clock time (sleeping, polling a watcher or a fake server until a deadline), an external program (`git`, `javac`, `node`), the operating system's scheduler, or a timing threshold. They pass on a quiet machine and may not on a loaded CI runner.
- **`live`**: calls a real provider API.

The default build excludes all three. Untagged is the default for a new test: write it so it needs nothing from the machine, and tag it only when it really does.

To see what is tagged: `grep -rl '@Tag("fragile")' --include=*Test.java .`

## Deciding where a new test goes

1. Does it call a real API? `live`.
2. Does it need a database, a model server, or several modules wired for real? `integration`.
3. Does it sleep, poll with a deadline, run `git` or `javac` or `node`, depend on the OS, or assert how long something took? `fragile`. (Prefer a fake clock or an injected sleeper over a real wait; then it needs no tag.)
4. Otherwise it is an ordinary test and stays untagged.

## Coverage

The default build runs JaCoCo and writes `target/site/jacoco` for every module. `ai-agent4j-tools` and `ai-agent4j-loom` also enforce minimums on the classes where a regression would matter most; those rules run in the default build, so they must be met by the default tests alone.

## CI mapping

- **Pull requests and pushes:** `mvn verify` (default tests and coverage gates), and the release check (`-Prelease -Dgpg.skip=true`) so a broken javadoc or a missing field is found before a release, not during one.
- **Weekly, and on request:** `mvn -Pextended verify`, and the dependency vulnerability scan.
- **A version tag:** `mvn -Prelease deploy`, which runs the default tests again before anything is uploaded. See [PUBLISHING.md](PUBLISHING.md).
