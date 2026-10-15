# llm4j-spring-boot-starter

Spring Boot auto-configuration for llm4j. Add it and you get beans; leave it out and llm4j is the same pure Java it always was.
No llm4j module depends on this one, and the starter adds no behaviour: each bean is what you would write with `new` yourself.

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>llm4j-spring-boot-starter</artifactId>
    <version>5.0</version>
</dependency>
<!-- for the Loom runtime, add Loom too (optional, so the starter does not push it onto you) -->
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-loom</artifactId>
    <version>5.0</version>
</dependency>
```

## What you get

| Bean | When | It is |
|---|---|---|
| `LLMClient` | `llm4j.provider` is set | `new DefaultLLMClient(provider)` for gemini, anthropic, ollama or sarvam |
| `ToolRegistry` | always | the core registry holding every `Tool` bean you declare |
| `ReActAgent.Builder` (a new one per injection) | there is an `LLMClient` | `ReActAgent.builder()` with the client, your tools and `llm4j.agent.*` applied |
| `LoomScript`, Loom `ToolRegistry`, `LLMClientFactory`, `LoomRuntime` | `ai-agent4j-loom` is on the class path **and** `llm4j.loom.script` is set | the parsed script, its tools, its models, and a runner |

Each backs off to a bean of your own type. Nothing is scanned, no tool appears that you did not declare, and nothing calls a model at start-up.
`llm4j.enabled=false` turns it all off.

```yaml
llm4j:
  provider: anthropic
  model: claude-sonnet-5-5
  api-key: ${ANTHROPIC_API_KEY}     # never a literal in a file you commit
  # base-url, timeout, connect-timeout are optional
  agent:                      # optional defaults for ReActAgent.Builder
    system-prompt: You are a concise support assistant.
    max-iterations: 8
    temperature: 0.2
  loom:
    script: classpath:workflows/support.loom   # or file:/etc/app/support.loom
    loot: classpath:workflows/tools.loot       # optional: tool name = class
```

## Using the beans

```java
@Service
class Support {
    private final LLMClient llm;            // llm4j.provider
    private final ToolRegistry tools;       // your Tool beans
    private final ObjectProvider<ReActAgent.Builder> agents;  // a builder, see below
    private final LoomRuntime loom;         // llm4j.loom.script

    Support(LLMClient llm, ToolRegistry tools, LoomRuntime loom) { ... }

    String ask(String question) {
        ReActAgent agent = agents.getObject().build();   // one agent per conversation
        return agent.run(question).getAnswer();
    }

    void handle(String ticket) {
        loom.run("Triage", Map.of("ticket", ticket));          // blocks
        LoomRun run = loom.start("Triage", Map.of(...));       // or: a handle to await, steer, pause, cancel
    }
}

@Component
class Calculator implements Tool { ... }   // lands in the ToolRegistry, and in Loom's, by getName()
```

Without Spring, the same thing is: `new HarnessExecutor(script, tools, models)`, `initialize()`, `executeWorkflow(...)`.

## Things worth knowing

- **You get a builder, not a `ReActAgent`.** An agent keeps a conversation, so one shared bean would mix your users' chats. Inject `ObjectProvider<ReActAgent.Builder>` (or the builder itself; each injection is a fresh one), add anything else you want, and `build()` per conversation.

- **One executor for each run.** A `HarnessExecutor` holds one run's variables, journal and budgets, so `LoomRuntime` makes a new, initialized one per `run`/`start`. A script that is invalid, or whose model has no key, fails there, listing every problem, not mid-run. The application itself still starts.
- **Configure executors with a bean.** Declare a `LoomExecutorCustomizer` (a `HumanInterface`, a run journal, an audit logger, a `SecretStore`); it runs on every new executor before `initialize()`.
- **Model keys** for Loom scripts are read through the Spring `Environment`, so `GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `SARVAM_API_KEY` and `OLLAMA_BASE_URL` work as environment variables or properties. A `SecretStore` bean, if you have one, is used first. Declare your own `LLMClientFactory` bean to replace the lookup.
- **Imports** (`import "other.loom"`) resolve beside a `file:` script; a script inside a jar cannot import other files.
- **A key you forgot fails at start-up**, naming the property to set, for `LLMClient` (`llm4j.api-key`).
- **Security stays in the script.** The starter does not weaken `weave audit` or tool allow-lists; run `weave audit` on the script as you would anywhere.
