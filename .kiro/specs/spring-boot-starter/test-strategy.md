# Test Strategy: Spring Boot Starter

The checks, fixtures and gates are in `verification.md`; this page only says how the tests are organised.

## Where tests live

| Code | Module | Style |
|---|---|---|
| `LoomHost`, `ScriptSource`, `RunSpecFile`, `HumanAnswers` | `ai-agent4j-loom` | plain JUnit, no Spring; the persistence matrix (`file` on `@TempDir`, `jdbc` on H2) via a parameterized base class |
| The starter's auto-configuration | `llm4j-spring-boot-starter` | `ApplicationContextRunner`; provider and webhook stand-ins on `MockWebServer`; no real network |
| Plain-Java equivalence | `llm4j-spring-boot-starter` | one `main`-style test built from the README's blocks, asserting the same outcomes as the Spring context |

## Rules

- **Hermetic by default.** No default-build test contacts a provider; a JUnit extension fails any test that opens a non-loopback connection. Live checks are `@Tag("live")`.
- **Time is controlled** with a `MutableClock` and a recording `Sleeper`; nothing sleeps for real beyond a 50 ms trigger poll.
- **Every check is tagged** `@Tag("SB-V<n>.<m>")`; `verify_spec.py` rejects an untagged check, an unknown tag, a `@Disabled` test and a test with no assertion.
- **One test per zero-magic rule** (V0), generated from the list of auto-configuration classes so new capabilities cannot skip them.
- **Library first.** `LoomHost` tests must pass before any starter test that depends on them is written.

## Coverage and matrix

JaCoCo rules are in G3 of the verification plan. CI runs the starter on JDK 17 and 21 against the lowest and the latest Spring Boot 3.x
(`-pl src/spring/llm4j-spring-boot-starter -am`), so the rest of the reactor is not built four times.
