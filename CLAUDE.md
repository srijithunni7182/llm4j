# CLAUDE.md

Guidance for Claude (and any other agent) working in this repository. It sets out how code is written here, how it is tested and what must never happen. The specific rules for driving a pull request are in the `steward` skill (`.claude/skills/steward/SKILL.md`), and that skill wins on PR conventions.

## The repository

llm4j is a Maven monorepo of Java libraries for building LLM agents and workflows. The group is `io.github.srijithunni7182`, the packages are under `io.github.llm4j`, and the code targets **Java 17** (CI runs it on JDK 17 and 21).

| Module | What it is |
|---|---|
| `src/ai-agent4j` | Core: `LLMClient`, providers (`provider/openai`, `anthropic`, `google`, `ollama`, …), agents, tools, RAG, memory, budgets, the secret store, PII masking |
| `src/ai-agent4j-addons` | Optional integrations built on the core |
| `src/ai-agent4j-tools` | Ready-made tools (HTTP, shell, email, …) with their network policy (`NetPolicy`) and redaction (`Redactor`) |
| `src/eval4j`, `src/eval4j-report` | Evaluation: golden datasets, judges, reports |
| `src/loom/ai-agent4j-loom` | Loom, the workflow language and runtime, and the `weave` CLI (check, audit, eval, run) |
| `src/loom/loom-inspect-core`, `loom-inspect`, `loom-fabric` | Watching a run: inspection rules, the live run page, the Fabric server |
| `src/loom/loom-maven-plugin`, `src/loom/ctk` | Maven plugin; the compatibility kit of Loom scenarios |
| `src/loom/vscode-loom`, `src/loom/graph-render` | VS Code extension (TypeScript) and the shared graph renderer (JS) |
| `src/spring/llm4j-spring-boot-starter` | Spring Boot auto-configuration |
| `src/engram`, `src/examples/*` | Not published; built only with `-Pextended` |

The reactor order matters: a failure in `ai-agent4j-loom` skips the modules after it. `loom-inspect*` and `loom-fabric` compile against the **installed** Loom jar, so after changing a Loom class they use, run `mvn -pl src/loom/ai-agent4j-loom -DskipTests install` first.

## Commands

| Command | Use |
|---|---|
| `make build` | Compile |
| `make test` / `mvn verify` | Fast, hermetic tests **and** the JaCoCo coverage gates. This is what "done" means. `mvn test` alone skips the gates. |
| `mvn -pl <module> -Dtest=FooTest -Djacoco.skip=true test` | Quick loop on one test while iterating |
| `make test-extended` | Also the `integration` and `fragile` tests, engram and the examples |
| `make test-live` | Real provider APIs. Needs keys and costs money; never run it unasked. |
| `make format-check` | Spotless (google-java-format, AOSP style) on core and addons. `mvn spotless:apply -pl <module>` fixes it. |
| `make release-check` | Sources and javadoc jars exactly as a release builds them |
| `mvn -P<mutation-profile> org.pitest:pitest-maven:mutationCoverage -pl <module>` | Mutation tests (see below) |

Don't run Maven with `-o`, because the JaCoCo plugin isn't available offline. The Loom module takes a few minutes to test, so use `-Dtest=` while iterating and run the whole module once before pushing.

## Principles

### 1. Write idiomatic Java

- Write modern Java 17, and match the style of the code around you.
  - **Records** for immutable value types.
  - **Sealed interfaces** with pattern-matching `switch` for closed sets of alternatives.
  - **Enums** for fixed vocabularies.
  - `var` only where the type is obvious from the right-hand side.
- Make classes `final` unless they're designed for extension. Make fields `private final`. Prefer immutable objects and return unmodifiable collections (`List.copyOf`, `Map.copyOf`).
- Validate at the boundary with `Objects.requireNonNull(x, "x")` and clear `IllegalArgumentException` messages. Fail fast. Don't let a `null` travel.
- `Optional` is for return values, never for fields or parameters. Never return `null` for a collection; return an empty one.
- **Builders** for objects with many optional settings (see `LLMConfig`). Use static factories with intention-revealing names (`SecretRef.of`, `SecretRef.literal`) rather than overloaded constructors.
- **Exceptions:**
  - Unchecked, and from the module's own hierarchy (`io.github.llm4j.exception.LLMException` and its subclasses, `SecretException`, …).
  - Never swallow one. Never catch `Exception` or `Throwable` without rethrowing or handling it meaningfully.
  - Keep the cause when wrapping.
  - Messages say what went wrong and what to do about it.
- Use try-with-resources for anything `AutoCloseable`. Don't use finalizers. Don't share mutable static state.
- Use the module's logging API: SLF4J in `ai-agent4j`, `java.util.logging` in `ai-agent4j-loom`. Use parameterised messages and never string concatenation. Never write to `System.out` from library code (CLI entry points are the exception).
- **Javadoc** on every public type and method of a published module. The release build runs javadoc, and a broken tag fails it. Say what the contract is, and leave out what the code obviously does.
- Don't add a dependency lightly. Each one is a supply-chain and compatibility cost for every user of the library. Jackson's version is managed once in the parent POM. No Lombok in published modules.

### 2. Good object-oriented design

- Model the domain with small types that have one clear responsibility and hold their invariants in the constructor. A `SecretRef`, a `Budget` or a `RetryPolicy` is better than a `String`, a `double` or an `int` passed around.
- **Tell, don't ask:** put behaviour next to the data it uses. Avoid anaemic data holders whose logic lives in a `*Utils` class.
- **Composition over inheritance.** The decorators here show the pattern:
  - `MaskingLLMClient` wraps an `LLMClient`, and `ChainedSecretStore` composes `SecretStore`s.
  - Add behaviour by wrapping an interface, not by subclassing a concrete class.
- Program to interfaces (`LLMClient`, `SecretStore`, `PIIDetector`, `Tool`). Concrete classes are an implementation detail behind a factory or builder.
- Keep visibility as narrow as it can be. Package-private is the default for anything that isn't part of the public API, and every `public` is a compatibility promise (see `docs/API_COMPATIBILITY.md`).

### 3. Clean, decoupled code: SOLID

- **Single responsibility:** a class has one reason to change. When a class needs "and" to describe it, split it.
- **Open/closed:** extend through new implementations of an interface, such as a new provider under `provider/<name>` or a new `SecretStore`. Don't add `if (provider == X)` branches to shared code.
- **Liskov substitution:** every implementation honours its interface's whole contract: its exceptions, nullability and thread-safety. A decorator behaves like what it wraps.
- **Interface segregation:** keep interfaces small and role-based. Don't make an implementor stub out methods it can't support.
- **Dependency inversion:**
  - Depend on abstractions, and pass collaborators in through the constructor.
  - Don't create service objects inside business logic.
  - Inject time (`Clock`), randomness, sleeping, HTTP and the environment so code can be tested without the real thing.
- Keep the module layering one-way. Core (`ai-agent4j`) knows nothing of tools, eval or Loom, and Loom knows nothing of inspect or fabric. Don't introduce a cycle.
- **Clean code:**
  - Methods that are short and do one thing. Names that say what things are, with no abbreviations.
  - No dead code, commented-out code or speculative generality (YAGNI).
  - No magic numbers: name them as constants.
  - Comments explain *why*, not *what*.
  - Leave the code a little better than you found it, but don't widen a change with unrelated refactors.

### 4. Test-driven development, verified by coverage and mutation tests

- **Write the test first.** Red, green, refactor:
  1. Write a failing test that states the behaviour.
  2. Write the simplest code that passes it.
  3. Refactor with the test green.
  4. Fix a bug by first writing the test that reproduces it.
- **Tools:** JUnit Jupiter (`@ParameterizedTest` for tables of cases), AssertJ for assertions, Mockito sparingly, and OkHttp `MockWebServer` for HTTP providers. Prefer hand-written fakes over mocks of types you own. Never mock a value type.
- **What to test:** behaviour through the public API, not private methods. One reason to fail per test. Name tests for the behaviour (`rejectsAKeyBoundToAnotherHost`). Use Arrange/Act/Assert.
- **Keep tests hermetic:** the default build has no network, no real model, no database, no wall-clock waits and no external programs. Use a fake `Clock` or an injected sleeper instead of `Thread.sleep`. When a test really needs the outside world, tag it per `docs/TESTING_STRATEGY.md`: `@Tag("integration")`, `@Tag("fragile")` or `@Tag("live")`.
- **Coverage (JaCoCo):**
  - Every build writes `target/site/jacoco`.
  - `ai-agent4j-tools` and `ai-agent4j-loom` enforce per-class line and branch minimums in `verify`, typically 0.85–0.90 line and 0.75–0.95 branch.
  - New code should meet the same bar even where no rule enforces it. A new branch in a gated class needs a test.
  - **Never lower a limit, add an exclusion or `@Disabled` a test to get green.** Write the missing test.
- **Mutation testing (PIT):**
  - Coverage shows a line ran. PIT shows a test would notice if the line were wrong.
  - Existing profiles: `mutation-invoker` and `mutation-classify` in `ai-agent4j` (credentials and OpenAPI auth at an 85% threshold; classification at 75%), and `mutation` in `ai-agent4j-loom` (the ladder language and engine).
  - When you change code in those areas, run the profile and don't let the score drop. Kill surviving mutants with better assertions; don't exclude them.
  - For new security-critical or decision-making logic, add its classes to a mutation profile.
- **Test the guards themselves:** security controls are tested like features. A test should fail if the guard is removed, so check it once by breaking the guard on purpose.
- Before declaring work done, run `mvn verify` on every module you touched, and `make format-check` where it applies. Report honestly what ran and what didn't.

### 5. Never leak an API key or secret

- **Never write a real credential anywhere in the repository:** code, tests, docs, Loom scripts, examples, commit messages, PR text, logs or comments. CI runs GitGuardian; treat a hit as an incident, not a nuisance.
- In tests, use obviously fake values (`"test-key"`, `"sk-test-000"`) and `InMemorySecretStore`.
- **Credentials flow through the secret store** (`io.github.llm4j.secret`):
  - Pass a `SecretRef`, never a `String` key, and resolve it per request with `resolveFor(host)`, so a key is sent only to hosts it's bound to.
  - Don't cache resolved values in fields.
  - A literal secret in a Loom script is a load error by design. Keep it that way.
- Keys come from environment variables, `EncryptedFileSecretStore` or the user's own vault. Local files such as `secrets.sh` and `.env` are git-ignored. Never print, `echo` or `cat` them, and never pass them as command-line arguments.
- **No secret in a `toString`, log line, exception message, trace, journal or eval report.** `SecretRef.toString()` is `secret:<name>`; follow that pattern. Errors name the secret, never its value. Redact through `Redactor`/scrubbing, never by hand.
- Don't make types that hold credentials `Serializable`.
- Only run `make test-live` when asked, with keys the user has set in their environment. Never ask the user to paste a key into the chat.

### 6. Secure coding

The model reasons and the code holds the authority: never rely on a prompt to enforce a rule. Read `docs/security/secure-by-construction.md` and `SECURITY.md` before changing anything security-relevant.

- **Treat all model output, tool results, web content, files and user input as untrusted.** Validate, bound and encode them before they reach a shell, a file path, a URL, SQL, HTML or a log.
- **Outbound requests:**
  - Go through `NetPolicy` / `HttpSupport`, which refuse loopback, private and cloud-metadata addresses (SSRF).
  - Stay under the declared base URL, and use HTTPS.
  - Never turn off TLS verification or hostname checks, even in a test helper.
- **Processes:** run only allow-listed programs, passing arguments as a list (`ProcessBuilder`). Never send a string through a shell.
- **Files:** resolve and normalise paths, then check they're still inside the allowed root (no `..` traversal and no symlink escape).
- **Deserialisation:**
  - Use Jackson into explicit types. Never enable default typing, and never use Java native deserialisation on untrusted data.
  - Disable external entities in any XML parser.
- **Least privilege and fail closed:**
  - Tools are declared per agent.
  - Effects can need approval, and budgets and loop bounds are checked before every model call.
  - A missing setting, an unknown value or a failed check denies; it never allows.
- **Data protection:** mask PII with `MaskingLLMClient` / `guard { pii: mask }` before it reaches a hosted model, and keep it out of logs.
- **Effects are journaled.** A retry, resume or rewind must never repeat a send or a payment, so keep new effects idempotent.
- Bound everything that grows: inputs, loops, retries, recursion, response sizes and timeouts on every network call.
- Use `SecureRandom` for anything security-related, and constant-time comparison for secrets and tokens.
- **Dependencies:**
  - Keep them current.
  - The OWASP dependency check runs weekly (`mvn dependency-check:check`).
  - Don't add a dependency with known high-severity CVEs.
- A new `weave audit` rule must be listed in `docs/security/weave-audit.md` (`SecurityDocTest` checks this). Map new risks to `docs/security/owasp-llm-top-10.md`.

## Things that change together

Several files are tested against each other; the full table is in the `steward` skill. The most common are:

- **Loom syntax:** update `LOOM_GUIDE.md` (its `loom` blocks are parse-tested), `LOOM_PROMPT.md`, `docs/guide/06-build-the-workflow.md`, the `llm4j-workflow-guide` skill, the VS Code grammar and snippets, and add a CTK scenario.
- **Graph node or edge kinds:** run `bash scripts/sync-graph-render.sh`, then `bash scripts/check-graph-render-sync.sh`.
- **A public API or file format:** update `docs/API_COMPATIBILITY.md`. When you add a component to a record, keep its old constructor.
- **Any `.loom` file:** it must pass `RepositoryScriptsTest`.

## Working conventions

- **Commits:** conventional prefixes (`feat:`, `fix:`, `refactor:`, `test:`, `docs:`, `chore:`), one concern per commit, and a message that explains why.
- **Pull requests:** follow `.github/pull_request_template.md` and fill in every section honestly.
- **Branches:** merge `main` into a branch rather than rebasing or force-pushing a shared one.
- **Docs:** update README, wiki or docs in the same change as the behaviour they describe.
- **Skills:**
  - Building or evaluating an agent or Loom workflow: `llm4j-workflow-guide`.
  - Deploying or observing one: `llm4j-loom-deployer`.
  - Designing an autonomous harness: `llm4j-harness-design`.
  - A red CI check: `babysit`.
