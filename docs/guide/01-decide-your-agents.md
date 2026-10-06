# 1. Decide your agents

**Goal:** a short list of agents, each with one job, its own tools, a persona and a prompt, written down before any code.

## Why

Evaluation, cost and security all follow from this list. An agent with one job has a rubric you can write in three
lines; an agent that "does everything" cannot be tested, and an agent with every tool is the one a prompt injection will use.

## How

1. **One job each.** Write it as a sentence: "checks claims against sources and says so when it cannot verify them."
   If it needs "and", consider two agents.
2. **Distinct on purpose.** A debate of six similar agents is one opinion six times. Give each a different *prong*
   (where it looks) and *temporal weight* (how it values recency).
3. **Fewest tools.** List the tools each agent may call. Fewer tools means a smaller attack surface and an easier test.
4. **Temperature by role.** Low for a verifier (0.1 to 0.2), higher for an ideas role (0.9). Write down why.
5. **Persona and prompts as data.** Use `AgentPersona` (or one of the ready-made `PersonaLibrary` personas) and keep prompt text
   in files with versions, so you can test and change a prompt without touching code. Which files depends on how you run the
   workflow (see "Two paths" below).

```java
AgentPersona rahul = AgentPersona.builder()
        .name("Rahul")
        .role("Adversarial Source Researcher")
        .addConstraint("Verify every claim literally; if a term cannot be verified, say so.")
        .build();
ReActAgent agent = ReActAgent.builder()
        .llmClient(client).persona(rahul).addTool(webSearch)
        .maxIterations(12).temperature(0.2)
        .promptRegistry(registry)
        .build();
```

```yaml
# prompts.yaml
prompts:
  agent_analyze:
    v1: "Analyze this problem as a {{role}}: {{problem}} ..."
    latest: "v1"
```

## Two paths: a script, or Java

Decide this once, because the rest of the guide forks on it only in a few places.

| | **Script path** (Loom is the runtime, run with `weave`) | **Java path** (agents built in Java, or Loom embedded in a Java host) |
|---|---|---|
| Where a prompt lives | a markdown file: `prompts/<id>.md` or `prompts/<id>/vN.md`, named in the script with `prompt: "id"` | the same files, read with `MarkdownFolderPromptRegistry`; or the older YAML file with `FileSystemPromptRegistry` |
| Pinning a version | `prompt: "id@v2"`, or `--prompt id@v2` for one run | `registry.get(id, "v2")`, or `HarnessExecutor.setPromptRegistry(...)` |
| Checking | `weave check` (missing files and versions, unused files), `weave audit` | the same commands on the script; your own tests on the agents |
| Needs Java and Maven | no | yes |

Nothing in the script path needs Java code, and the folder format is the same on both, so you can start with a script and
embed it later without moving a prompt. The Hexamind example in this guide uses the Java path with a YAML registry; the
`samples/newsletter` project in `loom/ai-agent4j-loom` is the script path.

```
newsletter/
  main.loom                    agent Researcher { model: "..."  prompt: "researcher" }
  prompts/
    researcher/v1.md
    researcher/v2.md
    writer.md
```

## Agent or task?

Not every step of a workflow should have a model behind it. Before you write a prompt, ask of each step:

| If the step... | Make it a... | Because |
|---|---|---|
| reads messy input, writes, judges, argues, summarises | **agent** | it needs reasoning |
| checks a rule, calculates, looks something up, formats a record | **task** (plain Java) | exact, free, unit-testable |
| moves money, sends, creates, deletes, writes an audit record | **task** (plain Java) | a model must never decide *whether* or *with what arguments* |
| has to give the same answer for the same input, every time | **task** | determinism is the requirement |

The usual shape is **the model reads, the code decides and acts**: a support bot lets an agent extract `{order_id, amount}` from the customer's message, then a
`RefundPolicy` task decides, then an `IssueRefund` task pays. A prompt-injected customer can fool the agent into saying "100000", but not the policy. A task needs no prompt, no
temperature and no rubric: you test it like any function (see [Creating Tasks](../../ai-agent4j/wiki/Creating-Tasks.md)), so it costs nothing at stages 2 to 5.

## Worked example: Hexamind

| Agent | Job | Prong | Temp | Tools |
|---|---|---|---|---|
| Alex | engineering feasibility, quantified | whitepapers, docs | 0.3 | search, datetime |
| Jordan | market direction, real-time | news, social, financial | 0.5 | search, datetime |
| Sasha | long-range "what if", flagged as speculation | futures | 0.9 | search, datetime |
| Dr. Aris | what the literature supports | journals | 0.1 | search, datetime |
| Casey | what customers experience | support, accessibility | 0.6 | search, datetime |
| Rahul | verify sources, hunt counter-examples | any | 0.2 | search, datetime |
| Moderator / Coordinator / Debunker | flag a fabricated premise / write the consensus / explain a debunk | none | 0 / 0.3 / 0.3 | none |

The three workflow roles have **no tools**, which is why the security audit in chapter 7 finds nothing for them.

## Gate

Every agent has: a one-sentence job, a tool list, a temperature with a reason, and prompt ids in a registry.
Every step that moves money, sends something or must be exact is a **task**, not an agent.
If two agents' jobs overlap, merge or sharpen them now; it is free.
