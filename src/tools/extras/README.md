# llm4j tools: extras

> 🧰 **Browse every ready-made tool, with what it can do for an agent, in the [Tools catalog](../../../docs/TOOLS_CATALOG.md).**

The parent (`ai-agent4j-tools-extras-parent`) of the optional tool modules: tools that need a dependency beyond the core and
Jackson, so they are kept out of the light [`ai-agent4j-tools`](../ai-agent4j-tools/) module. A user adds an extras module
only when they want its tool, and pays nothing otherwise. Why the split exists is in [the tools README](../README.md).

| Module | Tool | Dependency beyond the core and Jackson |
|---|---|---|
| [`ai-agent4j-tools-search`](ai-agent4j-tools-search/) | `search`: web search through a provider you choose and key | none |
| [`ai-agent4j-tools-documents`](ai-agent4j-tools-documents/) | `documents`: the text of PDF, DOCX and HTML files, with pages and paragraphs | Apache PDFBox (and a `-weave` jar with it inside) |
| [`ai-agent4j-tools-git`](ai-agent4j-tools-git/) | `git`: status, diff, log, branch, commit and an approved push, through the sandbox | none |
| [`ai-agent4j-tools-browser`](ai-agent4j-tools-browser/) | `browser`: open, read, click, fill and screenshot pages in an installed Chrome, every request checked | none (a Chrome you install) |

## What an extras module is

- **One artifact per tool,** named `ai-agent4j-tools-<name>`, in a folder of the same name here.
- **It depends on the core and the light tools module,** plus what its tool needs. The versions of those heavy dependencies
  are managed in this parent's `pom.xml`, so two extras modules never bring two copies of one library.
- **Its kinds are found when its jar is on the class path**, through the JDK's `ServiceLoader`; nothing else in Loom or
  the core changes when it is added.
- **It pins its own dependencies:** it turns on the `weight-rule` execution of the tools parent and has a test with an
  allow-list of what it depends on, each with a licence compatible with Apache 2.0.
- **It has a README** that says what it is for and who needs it, gives the options and a working example, says what
  `weave audit` sees and how to run it safely, and says how to add it to `weave` and to a Maven project.

## Adding a module

1. Create `ai-agent4j-tools-<name>/` with a `pom.xml` whose parent is `ai-agent4j-tools-extras-parent`, and list it under
   `<modules>` in this folder's `pom.xml`.
2. Put the version of each new dependency in `<dependencyManagement>` here, and justify it in the pull request (size,
   transitive dependencies, licence, maintenance, known CVEs), as the repository's `CLAUDE.md` asks.
3. Name `maven-dependency-plugin` in the module's `<build><plugins>` and add its weight test.
