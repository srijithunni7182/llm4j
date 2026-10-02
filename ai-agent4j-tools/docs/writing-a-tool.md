# Writing a tool

[← all tools](README.md)

A tool in this library has up to four parts. Keep each in its own class, and give the one that decides what a model may do
its own tests.

1. **A kind**: extend `GenericKind`. It says the kind's `name()`, its `required()`, `optional()`, `prefixes()` and `secrets()`
   options, and implements `validate(Options, baseDir)` (throw `OptionException` naming the option) and
   `build(name, Options, baseDir, EffectContext)`. `Options` gives typed reads: durations, sizes, integers in a range, booleans,
   choices, lists, headers.
2. **A tool**: extend `GenericTool` and implement `run(args, idempotencyKey)`: return the text the agent sees, throw `ToolRefusal`
   to refuse (it becomes `Error: <reason>` and counts as provably not done), or throw `UnknownOutcomeException` when an action
   may have happened but you cannot tell. The base class scrubs secrets from everything that leaves, turns any other exception
   into text, and audits the call, so the tool never throws at the agent. Read text arguments with `text(args, key)` or
   `optionalText(args, key)`, which refuse lists and objects.
3. **A guard**, if the tool decides what a model may do: a small class with no I/O that takes a value and either accepts it or
   says why not (`NetPolicy`, `SqlGuard`, `PathGuard` and `RequestPath` are examples). Test it with a table of good and bad
   inputs, a fuzz test, and a hostile-call test.
4. **Its effect behaviour**, if the tool changes anything outside the process. Every `GenericTool` is an `Effectful`, and a
   plain read-only tool leaves the defaults. Otherwise override `isEffect(args)` (per call: a `GET` is not an effect, a `POST`
   is), `policy()` (an `EffectPolicy`: what to do when an earlier attempt's outcome is unknown, whether the receiver deduplicates,
   a per-run cap) and `target(args)` (a short, non-sensitive description for audit). `GenericKind.create` then wraps the tool in
   the effect journal, and the model gets exactly-once behaviour without the tool doing anything more. Use `idempotencyKey`
   (stable across a repeat) if the receiver can deduplicate.

## Checklist

- Secrets are listed in `secrets()` and never appear in an error, a trace or a result.
- `check` touches neither the network nor the files, and names the option that is wrong.
- Every refusal is text starting `Error:` and says which rule refused, without echoing a secret.
- A call that may have been delivered but whose answer was lost throws `UnknownOutcomeException`, not `ToolRefusal`.
- Tests: the options table, each refusal, the failure paths, a hostile-call test, and a test that a repeat of the same call on the
  same journal does not repeat the effect. Add the class to the JaCoCo branch-coverage list in `pom.xml` if it is a guard.
- A page in this folder, with a Java example that `DocumentedExamplesTest` can check.

To use the new tool from Loom scripts, register an instance in Loom's `ToolFactory` with `GenericKindAdapter`.
