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
   in a `PromptRegistry` file with versions, so you can test and change a prompt without touching code.

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
If two agents' jobs overlap, merge or sharpen them now; it is free.
