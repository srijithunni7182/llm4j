# Prompt Registry Guide (xAI Standard)

To adhere to **Explainable AI (xAI)** standards and maintain operational control, `ai-agent4j` supports externalizing prompts into a versioned registry. This allows you to manage prompt changes independently of your Java code, supporting **templating**, **versioning**, and **hot-reloading**.

## Why use a Prompt Registry?

- **Separation of Concerns**: Prompts are treated as configuration/data, not hard-coded strings.
- **Versioning**: Easily roll back to previous prompt versions if a new one performs poorly.
- **Hot-Reloading**: Update prompts at runtime without restarting your application.
- **xAI Compliance**: Maintain an audit trail of exactly which prompt version was used for a specific decision.

## Setting up the Registry

Prompts are typically stored in a `prompts.yaml` file.

### prompts.yaml Structure

```yaml
prompts:
  agent_system_prompt:
    v1: "You are a helpful assistant."
    v2: "You are a specialized {{role}}."
    latest: "v2"
  
  error_handler_prompt:
    v1: "An error occurred: {{error}}. Please suggest a fix."
    latest: "v1"
```

## Using the Registry in Java

### 1. Initialize the Registry

The `FileSystemPromptRegistry` monitors your YAML file for changes.

```java
import io.github.llm4j.agent.prompt.FileSystemPromptRegistry;
import java.nio.file.Paths;

// Initialize registry (monitors the file for hot-reloading)
FileSystemPromptRegistry registry = new FileSystemPromptRegistry(Paths.get("config/prompts.yaml"));
```

### 2. Fetch and Render Templates

Templates use the Handlebars-style `{{variable}}` syntax.

```java
// Fetch the 'latest' version of the prompt
PromptTemplate template = registry.get("agent_system_prompt").get();

// Render with variables
String rendered = template.render(Map.of("role", "Java Security Expert"));
```

### 3. Integration with ReActAgent

You can pass the registry directly to the agent builder. This ensures the agent uses the central configuration for its core instructions.

```java
ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .promptRegistry(registry)
    .systemPromptId("agent_system_prompt") // Automatically fetches 'latest'
    .build();
```

## Best Practices

- **Consistent Naming**: Use clear, descriptive IDs for your prompts.
- **Semantic Versioning**: Use versions like `v1`, `v2`, or dates to track changes.
- **Environment Specifics**: You can use different YAML files for development, staging, and production.

---

*Related: [xAI Standards Guide](xAI_BEYOND_BLACK_BOXES.md)*
