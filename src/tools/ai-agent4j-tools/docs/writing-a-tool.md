# Writing a tool

[← all tools](README.md)

This guide takes you from nothing to a working, tested, audited tool in about twenty minutes. You build two small tools
and every line of code on this page is real: a test compiles and runs both, and fails if this page and the code ever
differ.

- **`wordcount`** counts the words in text. It reads nothing and changes nothing, so it shows the basic shape.
- **`receipt`** appends a line to a log file. It *changes* something, so it shows what a tool must do to be safe to retry.

**You need:** Java 17 and Maven, a checkout of this repository, and a reason the [existing tools](README.md) don't cover.
You do not need Loom to follow along: a tool is plain Java, and Loom is one of the places that can run it.

## The steps

| | Step | You end up with |
|---|---|---|
| 1 | [Decide where it lives](#1-decide-where-it-lives) | A module and a package |
| 2 | [Write the kind](#2-write-the-kind) | A class that checks options and builds the tool |
| 3 | [Write the tool](#3-write-the-tool) | A class that does the work and refuses what it must |
| 4 | [Say what it reaches](#4-say-what-it-reaches) | A profile the audit can read |
| 5 | [Test it](#5-test-it) | Tests for options, calls, refusals and hostile input |
| 6 | [If it changes something](#6-if-it-changes-something) | A tool that is safe to retry |
| 7 | [Use it](#7-use-it) | The tool in Java and in a Loom script |
| 8 | [Run the checks](#8-run-the-checks) | A green build and a clean audit |

## How the pieces fit

```text
  a host (Loom, Spring, your own Java)
        │   options as text: { "max_chars": "5000" }
        ▼
  ┌─────────────┐  check(options)   → what is wrong with them, or null   (no network, no files)
  │    KIND     │  profile(options) → what a tool of this kind reaches   (for the audit)
  │ GenericKind │  create(...)      → builds the TOOL
  └─────┬───────┘
        ▼
  ┌─────────────┐  execute(args) → text the agent sees, never an exception
  │    TOOL     │  an effect is recorded in the JOURNAL first, so a repeat is not done twice
  │ GenericTool │
  └─────────────┘
```

A **kind** is built from text options (so a script can declare it) and is asked questions that must not touch anything.
A **tool** is what an agent calls. Keep them in separate classes.

---

## 1. Decide where it lives

| Your tool | Put it in | Why |
|---|---|---|
| Needs only the JDK, Jackson and the core | `src/tools/ai-agent4j-tools`, package `io.github.llm4j.tools` | The light module. A test pins its dependencies |
| Needs any other library | A new module under [`src/tools/extras`](../../extras/README.md) | Users add it only if they want it |
| Is only for your application | Your own project | Nothing to publish. Step 7 shows how Loom finds it |

The weight rule is a test (`WeightRuleTest`): adding a dependency to the light module fails the build until the test is
changed on purpose. Read the [tools README](../../README.md#the-weight-rule) before you reach for a library.

The examples below live in `src/test/java/io/github/llm4j/tools/docs/example/` so they stay out of the published jar.

## 2. Write the kind

A kind names itself, lists its options, checks them and builds the tool. Extend `GenericKind`.

```java
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.Options;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** {@code tool Count { use: wordcount  max_chars: 5000 }}: counts words, lines and characters. */
public final class WordCountKind extends GenericKind {

    static final int DEFAULT_MAX_CHARS = 10_000;
    static final int MAX_CHARS_CEILING = 100_000;

    @Override
    public String name() {
        return "wordcount";
    }

    @Override
    public Set<String> optional() {
        return Set.of("max_chars");
    }

    @Override
    protected void validate(Options options, Path baseDir) {
        options.integer("max_chars", DEFAULT_MAX_CHARS, 1, MAX_CHARS_CEILING);
    }

    @Override
    protected Tool build(String name, Options options, Path baseDir, EffectContext context) {
        int maxChars = options.integer("max_chars", DEFAULT_MAX_CHARS, 1, MAX_CHARS_CEILING);
        return new WordCountTool(name, maxChars, context);
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        return ToolProfile.pure("counts the words of text it is given");
    }
}
```

What each part does:

| Method | Its job | Rules |
|---|---|---|
| `name()` | The word a script uses: `use: wordcount` | Lower-case letters, digits and `_`. A name is registered once, so you cannot take `file` or `shell` |
| `required()`, `optional()`, `prefixes()` | The option names the kind accepts | A host rejects a name that is not listed (Loom does: `weave check` names it). `prefixes()` accepts a family such as `header.` |
| `secrets()` | Options that hold credentials | They are scrubbed from every result, and a host accepts them only from the environment or a secret store |
| `validate(options, baseDir)` | Throws `OptionException` naming the option | **No network, no file reads.** This runs at `weave check`, before anything exists |
| `build(name, options, baseDir, context)` | Makes the tool from the same options | Read each option the way `validate` does, so the two cannot disagree |
| `profile(options)` | What a tool of this kind reaches | Step 4 |

`Options` reads text options as typed values, and each read fails with a message that names the option:

| Read | Gives | Example |
|---|---|---|
| `integer(name, fallback, min, max)` | An `int` in a range | `max_chars: 500` |
| `size(name, fallback, max)` | Bytes from `64k`, `1m` | `max_bytes: 1m` |
| `duration(name, fallback, max)` | A `Duration` from `20s`, `2m` | `timeout: 20s` |
| `bool(name, fallback)` | A `boolean` | `overwrite: true` |
| `choice(name, fallback, allowed...)` | One of a fixed set | `format: slack` |
| `list(name)` | A comma-separated list, trimmed | `hosts: "a.com, b.com"` |
| `require(name)`, `string(name, fallback)`, `has(name)`, `get(name)` | Raw text | |

**Always give a ceiling.** `max_chars` has a default *and* a hard maximum (`MAX_CHARS_CEILING`), so a script cannot turn
the limit off. Every limit in this library works that way.

## 3. Write the tool

The tool does the work. Extend `GenericTool` and implement `run`.

```java
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.tools.GenericTool;
import io.github.llm4j.tools.Redactor;
import io.github.llm4j.tools.ToolRefusal;
import java.util.Map;

/** Counts the words, lines and characters of the {@code text} argument. */
final class WordCountTool extends GenericTool {

    private final int maxChars;

    WordCountTool(String name, int maxChars, EffectContext context) {
        super(
                name,
                "wordcount",
                "Counts the words, lines and characters of text. Give text.",
                Redactor.NONE,
                context);
        this.maxChars = maxChars;
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) {
        String text = text(args, "text");
        if (text.length() > maxChars) {
            throw new ToolRefusal(
                    "text is " + text.length() + " characters; the most allowed is " + maxChars);
        }
        int words = text.strip().split("\\s+").length;
        int lines = text.split("\r?\n", -1).length;
        return "{\"words\":" + words + ",\"lines\":" + lines + ",\"characters\":" + text.length() + "}";
    }
}
```

`GenericTool` handles what every tool needs, so `run` stays short:

| You do | The base class does |
|---|---|
| Return the text the agent sees | Scrubs secrets from it, and from every error and trace line |
| `throw new ToolRefusal("reason")` to say no | Turns it into `Error: reason`, and records the call as *provably not done* |
| Let any other exception escape | Turns it into `Error: ...` text: **a tool never throws at the agent** |
| Read arguments with `text(args, key)` or `optionalText(args, key)` | Refuses a missing, blank, list or object value, so a model cannot slip structure through |

**Write refusals for the agent to read.** `text is 11 characters; the most allowed is 10` tells a model what to fix.
`invalid input` does not. Name the rule that refused, and never echo a secret.

**Return something easy to parse.** A small JSON object (as here) beats prose when a workflow reads fields from the
result. The result of a tool a model can steer is still text a model can influence; see
[the safety model](safety.md).

## 4. Say what it reaches

`weave audit` has to know what an agent holding your tool could do. The kind tells it, with `profile(options)`:

```java
@Override
public ToolProfile profile(Map<String, String> options) {
    return ToolProfile.pure("counts the words of text it is given");
}
```

| Question | `true` when a tool of this kind... | Effect on the audit |
|---|---|---|
| `untrusted` | reads content from outside: a web page, a file someone sent, a program's output | Its result is treated as text an attacker may have written |
| `privateData` | reaches the user's own data: files, a database, a mailbox | Part of the lethal trifecta check (LA01) |
| `outward` | sends or changes something beyond the run: a message, a request, a push | The other leg of the trifecta, and a reason to ask for approval |
| `changes` | a call changes something | The tool needs `approve:` or an explicit `unattended` |

`ToolProfile.pure(note)` is for a tool that reaches nothing. Otherwise use `ToolProfile.of(untrusted, privateData,
outward, changes, note)` and add `.journaled(true)` when the tool is a `GenericTool` that changes something.

The options arrive **as written in the script**, so `env.NAME` is that text and never its value. A profile must not touch
the network or the files.

> **Say it, or be assumed the worst.** A kind that does not override `profile` is treated as reaching everything and
> changing things, so the audit flags every agent that holds it. A test below pins this.

## 5. Test it

Write these tests before you rely on the tool. Each answers one question, and the example's test class has all of them
(`WritingAToolExamplesTest`).

| Question | Test |
|---|---|
| Does `check` name a bad option? | A table of good and bad values |
| Does a normal call work? | One call, one exact answer |
| Is every refusal an `Error:` that says why? | One test per refusal |
| Can a hostile call get past it? | Lists, objects, `null`, huge values, line breaks |
| Does the audit see the right thing? | The profile's four flags |

Build the tool through its kind, with `RecordingEffects` (a test double for the run's audit and journal):

```java
Tool counter(String maxChars) throws Exception {
    return new WordCountKind().create("Count", Map.of("max_chars", maxChars), dir, effects);
}
```

**The options table.** One row per way an option can be wrong; assert on the option's name:

```java
@ParameterizedTest
@CsvSource(delimiter = '|', nullValues = "null", value = {
        "max_chars=500|null",
        "max_chars=0|max_chars: must be",
        "max_chars=100001|max_chars: must be",
        "max_chars=lots|max_chars: must be" })
void checkNamesTheOptionThatIsWrong(String option, String problem) { ... }
```

**A normal call, and a refusal that says why:**

```java
assertThat(counter("100").execute(Map.of("text", "one two\nthree")))
        .isEqualTo("{\"words\":3,\"lines\":2,\"characters\":13}");

assertThat(counter("10").execute(Map.of("text", "x".repeat(11))))
        .startsWith("Error:")
        .contains("the most allowed is 10");
```

**Hostile calls.** A model chooses the arguments, so try what a model might send:

```java
assertThat(tool.execute(Map.of("text", List.of("a")))).startsWith("Error:");   // a list is not text
assertThat(tool.execute(null)).startsWith("Error:");                           // no arguments at all
```

**Break the guard on purpose, once.** A guard is only tested if its test fails when the guard is gone. Comment out the
`maxChars` check in `WordCountTool`, run the tests, watch `textOverTheLimitIsRefusedAndSaysWhich` fail, then put it back.

Run just this class while you work:

```bash
mvn -pl src/tools/ai-agent4j-tools -Dtest=WritingAToolExamplesTest -Djacoco.skip=true test
```

> If your tool decides what a model may do (a URL, a path, a query, a program name), put that decision in a small guard
> class with no I/O that either accepts a value or says why not, and test it with a table of good and bad inputs.
> `NetPolicy`, `SqlGuard`, `PathGuard` and `RequestPath` are the examples, and each has a branch-coverage gate in `pom.xml`.

## 6. If it changes something

A tool that sends, writes, pays or deletes must be safe to **retry**. After a crash, a resume or a rewind, the same call
may arrive again, and it must not happen twice. You get this by declaring the tool an effect; the library does the rest.

```java
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.tools.GenericTool;
import io.github.llm4j.tools.Redactor;
import io.github.llm4j.tools.ToolRefusal;
import io.github.llm4j.tools.UnknownOutcomeException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/** Appends the {@code line} argument to {@code receipts.log}. A change, so it is journaled. */
final class ReceiptTool extends GenericTool {

    private static final int MAX_LINE = 500;

    private final Path dir;

    ReceiptTool(String name, Path dir, EffectContext context) {
        super(
                name,
                "receipt",
                "Records one line in the receipts log. Give line (one line of text).",
                Redactor.NONE,
                context);
        this.dir = dir;
    }

    @Override
    public boolean isEffect(Map<String, Object> args) {
        return true;
    }

    @Override
    public String target(Map<String, Object> args) {
        return "receipts.log";
    }

    @Override
    protected String run(Map<String, Object> args, String idempotencyKey) {
        String line = text(args, "line");
        if (line.length() > MAX_LINE || line.contains("\n") || line.contains("\r")) {
            throw new ToolRefusal("line must be one line of at most " + MAX_LINE + " characters");
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(
                    dir.resolve("receipts.log"),
                    line + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // The write may or may not have happened: say so, so a resume is not guessed.
            throw new UnknownOutcomeException("could not tell whether the line was written", e);
        }
        return "Recorded.";
    }
}
```

Three overrides make it an effect:

| Override | Say | Here |
|---|---|---|
| `isEffect(args)` | Whether *this call* changes something. A `GET` is not an effect and a `POST` is | Always `true` |
| `target(args)` | A short, non-sensitive description for the audit log. Never a body or a secret | `receipts.log` |
| `policy()` | What to do if an earlier attempt's outcome is unknown, whether the receiver deduplicates, a cap per run | The default |

`GenericKind.create` then wraps the tool in the effect journal. Before the call it records *about to do this*; after, *done*.
The same call on the same journal is answered `already done` and is not run again.

**Be honest about uncertainty.** If you cannot tell whether the action happened (the connection dropped after you sent the
bytes), throw `UnknownOutcomeException`, not `ToolRefusal`. A refusal means *provably not done*, which allows a retry; an
unknown outcome is held for the run's policy (`on_unknown: skip` or `retry`). If the receiver supports it, use the
`idempotencyKey` passed to `run`: it is the same across a repeat, so the receiver can deduplicate.

The kind says so in its profile, and the options say where it writes:

```java
import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.OptionException;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.SafePaths;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** {@code tool Receipts { use: receipt  dir: "receipts" }}: appends a line to a log file. */
public final class ReceiptKind extends GenericKind {

    @Override
    public String name() {
        return "receipt";
    }

    @Override
    public Set<String> required() {
        return Set.of("dir");
    }

    @Override
    protected void validate(Options options, Path baseDir) {
        try {
            SafePaths.inside(baseDir, options.require("dir"));
        } catch (IllegalArgumentException e) {
            throw new OptionException("dir: " + e.getMessage());
        }
    }

    @Override
    protected Tool build(String name, Options options, Path baseDir, EffectContext context) {
        return new ReceiptTool(name, SafePaths.inside(baseDir, options.require("dir")), context);
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        return ToolProfile.of(false, true, false, true, "appends to a log file in " + options.get("dir"))
                .journaled(true);
    }
}
```

`SafePaths.inside(baseDir, path)` refuses a path that climbs out of the base directory. It is used both in `validate` and
in `build`, so a bad `dir` is a load error and cannot be reached at run time either.

**The test that matters most for an effect:** a repeat does not repeat the action.

```java
Tool first = receipts();
Tool afterARestart = receipts();            // a new tool, the same journal

first.execute(Map.of("line", "order 17 shipped"));
String repeated = afterARestart.execute(Map.of("line", "order 17 shipped"));

assertThat(repeated).contains("already done");
assertThat(lines()).containsExactly("order 17 shipped");     // written once
```

Also test that a refused call writes nothing (`aLineThatIsNotOneLineIsRefusedAndNothingIsWritten`): a line break in a
log line is how an entry gets forged.

## 7. Use it

**From Java.** Create it from its kind. `create` takes the same options a script would write, already resolved:

```java
Tool count = new WordCountKind().create("Count", Map.of("max_chars", "5000"), Path.of("."));
String result = count.execute(Map.of("text", "the quick brown fox"));
// {"words":4,"lines":1,"characters":19}
```

Register it with an agent like any `Tool`. Use `create(name, options, baseDir, context)` with an `EffectContext` backed by
something durable when "not twice, even after a crash" matters.

**From a Loom script, in its own jar.** List the kind in `META-INF/services/io.github.llm4j.agent.tool.ToolKind`, one class
name per line, each with a public no-argument constructor:

```text
com.acme.tools.WordCountKind
```

Put the jar on the class path (`weave --classes acme-tools.jar`, or as a dependency of the project). Then:

```text
tool Count { use: wordcount  max_chars: 5000 }
agent Editor { model: "gemini-2.5-flash"  system: "Keep it under 200 words."  tools: [Count] }
```

`weave check` lists the kinds it found and the jar each came from. A kind that fails to load, or whose name is taken,
makes `weave check` fail and names the jar.

**Inside this repository.** A light tool also needs:

1. A line in Loom's `ToolFactory`: `register.accept(new GenericKindAdapter(new io.github.llm4j.tools.YourKind()));`
2. A page in this folder, linked from [the index](README.md), with a Java example that
   `DocumentedExamplesTest` can check (its options must pass the kind's own `check`).
3. A row for it in the [Tools catalog](../../../../docs/TOOLS_CATALOG.md) and a line in the current release notes.

## 8. Run the checks

Work from the small loop to the whole one:

| When | Run | It proves |
|---|---|---|
| While writing | `mvn -pl src/tools/ai-agent4j-tools -Dtest=YourToolTest -Djacoco.skip=true test` | Your tests pass |
| Before you commit | `mvn -pl src/tools/ai-agent4j-tools verify` | Every test, the weight rule and the coverage gates |
| After a docs change | `make docs-check` | Pages and examples are current |
| With Loom | `weave check flow.loom`, then `weave audit flow.loom` | The tool loads, and the audit says what it reaches |

`make test-fast` runs the tests of every module you touched, with the coverage gates. A guard class belongs on the
branch-coverage list in this module's `pom.xml`. Never lower a limit or skip a test to get green: write the missing test.

### The checklist

- [ ] The kind has a lower-case `name()`, and `validate` names the option that is wrong
- [ ] `validate` touches neither the network nor the files; `build` reads options the same way
- [ ] Every limit has a default **and** a ceiling
- [ ] Secrets are listed in `secrets()` and never appear in a result, an error, a trace or a `toString`
- [ ] Every refusal is `Error: ...`, says which rule refused, and is a `ToolRefusal`
- [ ] A call that may have happened but whose answer was lost throws `UnknownOutcomeException`
- [ ] `profile` says what the tool reaches, from the options as written, and has a test
- [ ] A tool that changes something overrides `isEffect`, `target` and `policy`, and a test shows a repeat is not repeated
- [ ] Tests cover the options table, each refusal, hostile arguments, and (for an effect) the repeat
- [ ] You broke each guard once and saw a test fail

### When something goes wrong

| You see | Likely cause |
|---|---|
| `weave check` says the kind is unknown | The jar is not on `--classes`, or the services file path or class name is wrong |
| `weave check` says the name is taken | Another kind already uses it; names are registered once |
| The audit flags every agent that holds the tool | `profile` is not overridden, so the kind is assumed to reach everything |
| A secret appears in a result | It is not listed in `secrets()`, or it is shorter than the minimum length of a credential |
| A repeated call runs twice | `isEffect` returns `false` for it, or the two tools use different journals |
| `WeightRuleTest` fails | A dependency was added to the light module. Move the tool to `extras`, or change the test on purpose |
| Docs test fails after you edit this page | A code block no longer matches the example class. Copy the class from the source again |

## Reference

| Type | Where | What it is |
|---|---|---|
| `GenericKind` | this module | Base of a kind: `validate`, `build`, and the final `check` and `create` |
| `GenericTool` | this module | Base of a tool: `run`, scrubbing, auditing, `Error:` results |
| `Options`, `OptionException` | this module | Typed option reads; a failure that names the option |
| `ToolRefusal`, `UnknownOutcomeException` | this module | Provably not done; may have been done |
| `Redactor` | this module | Removes secrets (plain, URL-encoded, Base64) from text |
| `SafePaths`, `PathGuard`, `NetPolicy` | this module | Keep a file or a request inside what is allowed |
| `ToolKind`, `ToolProfile`, `Effectful`, `EffectPolicy`, `EffectContext`, `EffectJournal` | `ai-agent4j`, `io.github.llm4j.agent.tool` | The contracts. They live in the core so another library can provide kinds without depending on this one |

See also: [concepts](concepts.md) (options, secrets, the journal), [the safety model](safety.md) and
[the other tools' pages](README.md) as worked references.
