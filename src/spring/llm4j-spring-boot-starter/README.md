# llm4j-spring-boot-starter

Spring Boot auto-configuration for llm4j. Add it and you get beans; leave it out and llm4j is the same pure Java it always was.
No llm4j module depends on this one, and the starter adds almost no behaviour: each bean is what you would write with `new` yourself.
(The one thing that is new, running workflows for an application, lives in Loom as `LoomHost`, and is usable without Spring.)

```xml
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>llm4j-spring-boot-starter</artifactId>
    <version>5.0</version>
</dependency>
<!-- optional, add only what you use; the starter tells you by name if a setting needs one you left out -->
<dependency>
    <groupId>io.github.srijithunni7182</groupId>
    <artifactId>ai-agent4j-loom</artifactId>        <!-- also ai-agent4j-tools, ai-agent4j-addons, spring-boot-starter-jdbc -->
    <version>5.0</version>
</dependency>
```

## The rules it keeps

1. **Nothing you did not configure.** With no `llm4j.*` settings you get an empty `ToolRegistry` and a `ToolCatalog`, nothing else.
2. **Yours wins.** Every bean backs off to a bean of your own type, and every decorator has a customizer.
3. **Plain Java underneath.** Each section below shows the `new` equivalent.
4. **Nothing starts by itself.** No model call, no network, no thread at start-up. Only `llm4j.loom.triggers.enabled` and `llm4j.observe.enabled` start anything, and only that.
5. **A mistake fails at start-up, naming the property.** Not on the first request.
6. **Secrets by name, never literal.** Keys are `…-secret` names resolved by a `SecretStore`; a literal is accepted but never printed.
7. `llm4j.enabled=false` turns it all off.

## Clients

```yaml
llm4j:
  secrets:
    store: env                       # env, encrypted-file or chained
  clients:
    default:
      provider: anthropic            # gemini, anthropic, ollama or sarvam
      model: claude-sonnet-5-5
      api-key-secret: ANTHROPIC_API_KEY
    cheap:
      provider: ollama
      model: llama3
  primary: default
  routing:
    strategy: none                   # none, fallback or cost-aware (over routing.clients)
```

**Bean:** `io.github.llm4j.LLMClient` (and `io.github.llm4j.spring.LLMClients`, every named client).
`llm4j.provider` / `model` / `api-key` / `base-url` are shorthand for a client named `default`.

Without Spring:

```java
import io.github.llm4j.DefaultLLMClient;
import io.github.llm4j.LLMClient;
import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.anthropic.AnthropicProvider;
import io.github.llm4j.secret.EnvSecretStore;
import io.github.llm4j.secret.SecretRef;

LLMClient client = new DefaultLLMClient(new AnthropicProvider(LLMConfig.builder()
        .defaultModel("claude-sonnet-5-5")
        .apiKey(SecretRef.of(EnvSecretStore.system(), "ANTHROPIC_API_KEY"))
        .build()));
```

## Prices, budgets, privacy and audit

```yaml
llm4j:
  pricing:
    models:
      claude-sonnet-5-5:
        input-per-million: 3
        output-per-million: 15
  budget:
    tokens: 2000000
    per-call-max-tokens: 8000        # required with a token cap
    window: day                      # minute, hour or day
  privacy:
    mask-pii: true
  audit:
    file: var/audit/llm.jsonl
```

**Bean:** `io.github.llm4j.budget.Budget`, `io.github.llm4j.budget.PriceTable`, `io.github.llm4j.audit.AuditLogger`;
the `LLMClient` is wrapped as `Masking(Budgeted(client))`, masking outermost.

Without Spring:

```java
import io.github.llm4j.LLMClient;
import io.github.llm4j.budget.Budget;
import io.github.llm4j.budget.BudgetedLLMClient;
import io.github.llm4j.privacy.MaskingLLMClient;

LLMClient inner = null; // the client from above
Budget budget = Budget.builder().tokens(2_000_000).build();
LLMClient guarded = new MaskingLLMClient(BudgetedLLMClient.builder(inner).budget(budget).perCallCap(8000).build());
```

## Agents, memory, prompts and skills

```yaml
llm4j:
  agent:
    system-prompt-id: triage         # or system-prompt: "text"
    max-iterations: 8
    tools: [calculator]
    context:
      window: 128000
      compaction: summarize          # any strategy the library or a jar provides
  memory:
    conversations: file
    dir: var/conversations
  prompts:
    location: classpath:prompts/
  skills:
    locations: classpath:skills/refunds.md
```

**Bean:** `io.github.llm4j.agent.ReActAgent.Builder` — **a new one per injection**, because an agent keeps a conversation and one shared agent would mix your users' chats.
Inject `ObjectProvider<ReActAgent.Builder>` and `build()` per conversation. Also `io.github.llm4j.agent.memory.ConversationStore` and `io.github.llm4j.agent.prompt.PromptRegistry`.

Without Spring:

```java
import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.ReActAgent;
import io.github.llm4j.agent.tools.CalculatorTool;

LLMClient client = null; // the client from above
ReActAgent agent = ReActAgent.builder().llmClient(client).addTool(new CalculatorTool()).maxIterations(8).build();
```

## Tools

```yaml
llm4j:
  tools:
    builtin: [calculator, current-time]
    base-dir: var/tools              # guarded file tools may touch only this folder
    custom:
      notify:
        kind: webhook                # webhook, email, http, file, shell or sql
        options:
          url: https://hooks.example.com/x
```

**Bean:** `io.github.llm4j.agent.tool.ToolRegistry` and `io.github.llm4j.spring.ToolCatalog`. Your `Tool` beans are added; nothing is scanned.
Guarded tools run only with the guards you set (`check`, allow-lists, `base-dir`), and a script needs a `.loot` reach line to use one.

Without Spring:

```java
import io.github.llm4j.agent.tool.ToolRegistry;
import io.github.llm4j.agent.tools.CalculatorTool;

ToolRegistry registry = new ToolRegistry();
registry.register(new CalculatorTool());
```

## RAG

```yaml
llm4j:
  rag:
    embedding: gemini                # gemini, onnx or djl
    api-key-secret: GEMINI_API_KEY
    store: memory                    # memory, file, pgvector or pinecone
```

**Bean:** `io.github.llm4j.agent.rag.store.VectorStore` (and an embedding provider). pgvector reuses `spring.datasource.*` when `rag.pgvector.url` is not set.

Without Spring:

```java
import io.github.llm4j.agent.rag.store.InMemoryVectorStore;
import io.github.llm4j.agent.rag.store.VectorStore;

VectorStore store = new InMemoryVectorStore();
```

## Loom

```yaml
llm4j:
  loom:
    script: classpath:workflows/support.loom     # or file:/etc/app/support.loom
    loot: classpath:workflows/tools.loot         # which tool names a script may reach
    validate: strict                             # strict or lenient
    persistence: file                            # none, file or jdbc
    dir: var/loom
    max-concurrent-runs: 32
    shutdown-grace: 30s
    audit:
      fail-on: high                              # refuse to start on a finding at this severity or worse
    triggers:
      enabled: true                              # starts the schedule/wake-up runner
```

**Bean:** `io.github.llm4j.spring.LoomRuntime`, over a `io.github.llm4j.loom.host.LoomHost`. A run that asks a person suspends (nothing is running);
answer it with `LoomRuntime.answer(...)` from a web request, a chat message, anywhere, and it carries on from its journal.
`approve:`, `sleep` and `rate_limits` need persistence (`file` or `jdbc`), or an explicit `allow-non-durable: true`.
`persistence: jdbc` uses the `DataSource` bean (name it with `llm4j.loom.datasource` when there are several) and creates its tables on an embedded database
(`initialize-schema: always | embedded | never`). Beans and events: `HumanQuestionEvent`, `TriggerFiredEvent`, `LoomExecutorCustomizer`, `LLMClientFactory`.

Without Spring:

```java
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.DefaultLLMClientFactory;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.host.LoomHost;
import io.github.llm4j.loom.host.Persistence;
import io.github.llm4j.loom.host.RunOutcome;
import io.github.llm4j.loom.host.RunRequest;
import java.nio.file.Path;
import java.util.Map;

LoomScript script = new LoomLoader().load("workflows/support.loom");
LoomHost host = LoomHost.builder(script, new ToolRegistry(), new DefaultLLMClientFactory(System::getenv, null))
        .persistence(Persistence.file(Path.of("var/loom")))
        .build();
RunOutcome outcome = host.run(RunRequest.of("Support", Map.of("ticket", "my card was charged twice")));
```

## The run page

```yaml
llm4j:
  observe:
    enabled: true                    # needs loom-inspect on the class path
    port: 8787
    bind: 127.0.0.1
    detail: metadata                 # full, truncated or metadata
```

Nothing is opened unless `enabled` is true, and it listens on loopback unless you say otherwise.

## Not in the starter (yet)

Evaluation (`eval4j`, `eval4j-report`) is not wired: run it from tests or the command line. Also not provided: a Spring MVC or WebFlux front end for answering suspended runs,
Micrometer metrics, and Spring Boot Actuator endpoints. Each is straightforward to add around the beans above, and none is needed to use them.

## Things worth knowing

- **One executor per run.** A `HarnessExecutor` holds one run's variables, journal and budgets, so the host makes a fresh one for each run. An invalid script, or a model with no key, fails when the application starts, listing every problem.
- **Model keys** for Loom scripts come from the `SecretStore` if you have one, then the Spring `Environment` (`GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `SARVAM_API_KEY`, `OLLAMA_BASE_URL`).
- **Resources need a prefix**: `classpath:` or `file:`. Imports (`import "other.loom"`) resolve beside the script in the same place, including inside a jar.
- **Security stays in the script.** The starter does not weaken `weave audit`: the trifecta check runs before any run and refuses the script at `llm4j.loom.audit.fail-on`.
