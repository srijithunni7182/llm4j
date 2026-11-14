# llm4j tools

> 🧰 **Browse every ready-made tool, with what it can do for an agent, in the [Tools catalog](../../docs/TOOLS_CATALOG.md).**

The tool modules of llm4j: tools an agent or a Loom workflow can use to act on the world, each built for the case where the
model, not you, chooses the arguments. This folder is their parent (`ai-agent4j-tools-parent`), which holds the rule that
keeps them light and the build settings they share.

## The layout

| Folder | Artifact (`io.github.srijithunni7182`) | What it is |
|---|---|---|
| [`ai-agent4j-tools/`](ai-agent4j-tools/) | `ai-agent4j-tools` | The light tools: `webhook`, `email`, `http`, `file`, `shell`, read-only `sql`, `compute`, `verify`, `scan`, `workspace` and `sandbox`. Depends on the core and Jackson only |
| [`extras/`](extras/) | `ai-agent4j-tools-extras-parent` | The parent of the optional tool modules: [`search`](extras/ai-agent4j-tools-search/), [`documents`](extras/ai-agent4j-tools-documents/), [`git`](extras/ai-agent4j-tools-git/) and [`browser`](extras/ai-agent4j-tools-browser/) |
| `pom.xml` | `ai-agent4j-tools-parent` | This parent |

You depend on a module, never on a parent. A parent pom is published only so the modules that inherit from it can be
resolved.

## The weight rule

Every dependency of a tool module becomes a dependency of every application that uses it. So:

- **The light module depends on the core, Jackson and, optionally, the mail library, and nothing else.** A tool that needs
  only the JDK and Jackson goes there.
- **A tool that needs anything more is its own module under `extras/`**, which a user adds only when they want that tool.
- **The rule is a test.** Before a tool module's tests run, Maven writes its resolved direct dependencies to
  `target/weight/dependencies.txt` (the `weight-rule` execution this parent defines), and a test in the module pins that
  list. In the light module it is [`WeightRuleTest`](ai-agent4j-tools/src/test/java/io/github/llm4j/tools/WeightRuleTest.java);
  adding a dependency fails the build until the test is changed, which makes the change visible in review.

## Adding a tool module

A light tool goes into `ai-agent4j-tools`. For one that needs a dependency, see [`extras/README.md`](extras/README.md).
Either way, follow [writing a tool](ai-agent4j-tools/docs/writing-a-tool.md) and the repository's
[CLAUDE.md](../../CLAUDE.md) (principle 8, keep the libraries lightweight).
