# Agent Personas

Agent Personas allow you to configure behavioral characteristics for your ReAct agents, making their responses more deterministic and role-specific.

## Overview

A persona defines:

- **Name**: The agent's identity
- **Role**: What the agent does
- **Expertise**: Areas of knowledge and skill
- **Tone**: Communication style
- **Constraints**: Behavioral rules and limitations
- **Custom Attributes**: Additional characteristics

## Using Pre-built Personas

The library includes 8 pre-built personas for common use cases:

```java
import io.github.llm4j.agent.persona.PersonaLibrary;

// Technical Analyst - data-driven and precise
ReActAgent analyst = ReActAgent.builder()
    .llmClient(client)
    .addTool(new CalculatorTool())
    .persona(PersonaLibrary.technicalAnalyst())
    .build();
```

### Available Personas

| Persona | Description | Best For |
|---------|-------------|----------|
| `technicalAnalyst()` | Data-driven, precise, analytical | Data analysis, technical reports |
| `creativeWriter()` | Expressive, imaginative, engaging | Content creation, storytelling |
| `customerSupport()` | Empathetic, helpful, solution-focused | Customer service, support tickets |
| `softwareDeveloper()` | Technical, systematic, best-practices | Code review, technical guidance |
| `researchScientist()` | Methodical, evidence-based, thorough | Research, academic work |
| `businessConsultant()` | Strategic, pragmatic, ROI-focused | Business strategy, consulting |
| `educator()` | Clear, patient, encouraging | Teaching, training, education |
| `medicalAdvisor()` | Careful, evidence-based, patient-centered | Health information (educational) |

## Creating Custom Personas

Build your own persona with the builder pattern:

```java
import io.github.llm4j.agent.persona.AgentPersona;

AgentPersona mathTutor = AgentPersona.builder()
    .name("Math Tutor")
    .role("friendly mathematics teacher")
    .expertise("Step-by-step problem solving and mathematics education")
    .tone("Encouraging, patient, and educational. Always explain the steps.")
    .addConstraint("Break down problems into simple steps")
    .addConstraint("Explain the reasoning behind each step")
    .addConstraint("Use examples to clarify concepts")
    .addCustomAttribute("teaching_style", "Socratic method")
    .build();

ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(new CalculatorTool())
    .persona(mathTutor)
    .build();
```

## How Personas Work

Personas modify the agent's system prompt by prepending persona-specific context. For example:

```
You are Math Tutor, a friendly mathematics teacher.

Your expertise: Step-by-step problem solving and mathematics education

Communication style: Encouraging, patient, and educational. Always explain the steps.

You must adhere to the following constraints:
- Break down problems into simple steps
- Explain the reasoning behind each step
- Use examples to clarify concepts

[... rest of ReAct system prompt ...]
```

## Best Practices

1. **Be Specific**: Define clear expertise and constraints
2. **Set Tone**: Specify the communication style you want
3. **Add Constraints**: Use constraints to enforce specific behaviors
4. **Test Variations**: Try different personas for the same task to see differences
5. **Combine with Tools**: Personas work best when combined with appropriate tools

## Example: Comparing Personas

```java
String question = "What is 15 * 23 + 47?";

// Technical Analyst - focuses on precision
ReActAgent analyst = ReActAgent.builder()
    .llmClient(client)
    .addTool(new CalculatorTool())
    .persona(PersonaLibrary.technicalAnalyst())
    .build();
AgentResult result1 = analyst.run(question);
// Expected: Precise calculation with data-driven explanation

// Educator - focuses on teaching
ReActAgent educator = ReActAgent.builder()
    .llmClient(client)
    .addTool(new CalculatorTool())
    .persona(PersonaLibrary.educator())
    .build();
AgentResult result2 = educator.run(question);
// Expected: Step-by-step explanation with educational context
```

## See Also

- [ReAct Agent](ReAct-Agent.md) - Core agent framework
- [Creating Custom Tools](Creating-Custom-Tools.md) - Building tools for agents
- [Examples](../src/main/java/io/github/llm4j/examples/PersonaAgentExample.java) - Working examples
