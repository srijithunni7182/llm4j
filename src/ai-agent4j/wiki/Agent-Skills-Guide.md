# Agent Skills Guide

Agent Skills allow you to inject structured domain knowledge into your agents using Markdown files. This keeps the agent's context organized and allows you to compose complex behaviors from reusable building blocks.

## What is an Agent Skill?

A **Skill** is a set of instructions or background knowledge provided in a Markdown format. When added to an agent, these skills are injected into the system prompt, appearing after the core persona but before the tool descriptions.

## Loading Skills

### Inline Skills

You can create a skill directly in your Java code:

```java
import io.github.llm4j.agent.skill.AgentSkill;

AgentSkill codingTips = AgentSkill.of(
    "Coding Standards",
    "Always write unit tests. Prefer composition over inheritance."
);
```

### Loading from Files or Classpath

For larger instruction sets, it is better to manage them as separate files.

```java
import io.github.llm4j.agent.skill.AgentSkill;
import java.nio.file.Path;

// Load from filesystem
AgentSkill fromFile = AgentSkill.fromFile(Path.of("skills/security-guidelines.md"));

// Load from classpath resource
AgentSkill fromClasspath = AgentSkill.fromClasspath("skills/coding-standards.md");
```

## Adding Skills to an Agent

You can add multiple skills to a single agent. The `ReActAgent` will automatically format them into a `## Skills` section in the prompt.

```java
ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addSkill(codingTips)
    .addSkill(AgentSkill.fromFile(Path.of("skills/api-design.md")))
    .build();
```

## Dynamic Skill Discovery

Advanced agents can search for and learn new skills dynamically at runtime from an external registry (like [SkillsMP](https://skillsmp.com)).

```java
import io.github.llm4j.agent.skill.RestSkillRegistry;
import io.github.llm4j.agent.tool.SkillDiscoveryTool;

// 1. Configure the Registry
RestSkillRegistry registry = RestSkillRegistry.builder()
    .baseUrl("https://skillsmp.com/api/v1/skills")
    .apiKey(System.getenv("SKILLSMP_API_KEY"))
    .build();

// 2. Add the Discovery Tool to the Agent
ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(new SkillDiscoveryTool(registry))
    .build();
```

If the agent encounters a task it does not know how to solve, it can use the `SkillDiscoveryTool` to `search` for and `read` relevant skills mid-conversation.

---

*Related: [Agent Personas](Agent-Personas.md)*
