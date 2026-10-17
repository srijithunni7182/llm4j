# Running a Loom workflow in a Spring Boot application

A developer guide. You have a Loom workflow (a `.loom` file) and a Spring Boot application, and you want the application to run the workflow: start it for a user, let it pause to ask a person, and carry on after a restart.
The [starter README](../src/spring/llm4j-spring-boot-starter/README.md) lists every capability; this page is the path through the Loom one.

Nothing here makes llm4j depend on Spring. The starter wires `LoomHost` (in `ai-agent4j-loom`, usable on its own) into your application; [the last section](#the-same-thing-without-spring) shows the plain-Java version.

## 1. Add the dependencies

```xml
<dependency>
  <groupId>io.github.srijithunni7182</groupId>
  <artifactId>llm4j-spring-boot-starter</artifactId>
  <version>5.0</version>
</dependency>
<dependency>
  <groupId>io.github.srijithunni7182</groupId>
  <artifactId>ai-agent4j-loom</artifactId>
  <version>5.0</version>
</dependency>
```

Loom is optional in the starter so it never arrives uninvited. If you set `llm4j.loom.script` without it, start-up fails and names the artifact to add.
Add `loom-inspect` as well if you want the live run page, `ai-agent4j-tools` if the workflow uses `use: webhook`, `email`, `http`, `file`, `shell` or `sql` tools, and `spring-boot-starter-jdbc` if you want runs kept in a database.

## 2. Put the workflow on the class path

`src/main/resources/workflows/support.loom`:

```loom
agent Triage { model: "gemini/gemini-2.5-flash" system: "You are Triage. Be brief." }

workflow Support(ticket_text) {
    delegate "Summarise this ticket in one line: {ticket_text}" to Triage -> digest
    human_prompt "Reply to the customer? Ticket: {digest}" -> go
    delegate "If the answer is yes, draft a short reply to: {digest}. Answer: {go}" to Triage -> draft_reply
}
```

Imports (`import "other.loom"`) are resolved next to the script, including inside a jar. A `.loot` file (which tool names a script may reach) goes beside it.
Scripts and `.loot` files load only from `classpath:` or `file:`, never from the network.

## 3. Configure it

```yaml
llm4j:
  loom:
    script: classpath:workflows/support.loom
    persistence: file            # none, file or jdbc: see "Persistence"
    dir: var/loom
```

Model keys: the workflow's models read `GEMINI_API_KEY`, `ANTHROPIC_API_KEY`, `SARVAM_API_KEY` or `OLLAMA_BASE_URL` from a `SecretStore` bean if you have one, otherwise from the Spring `Environment` (environment variables or properties).
A missing key stops the application at start-up and names the variable.

At start-up the starter also validates the script, listing every problem with its line, and runs the same security audit as `weave audit`. The application refuses to start on a finding at `llm4j.loom.audit.fail-on` (default `high`; `none` switches the gate off with a warning).
Nothing calls a model at start-up.

## 4. Run it

```java
@Service
class SupportService {
    private final LoomRuntime loom;

    SupportService(LoomRuntime loom) { this.loom = loom; }

    RunOutcome open(String ticket) {
        return loom.run(RunRequest.of("Support", Map.of("ticket_text", ticket)));
    }
}
```

`run` blocks and returns how the run ended:

| Outcome | Meaning |
|---|---|
| `Completed` | it finished; `variables()` holds what the workflow set (`digest`, `go`, `draft_reply` above) |
| `Suspended` | it is waiting (a person, a timer or a limit); nothing is running; `runId()` and `stepId()` say where |
| `Stopped` | someone stopped it |

A failure is thrown as `LoomRunFailedException` carrying the run id. `loom.start(request)` returns a `LoomRun` handle instead of blocking (pause, steer, cancel, await).
Give a request an id with `.withRunId("ticket-42")` if you want to find it later; otherwise the host makes a UUID. Ids are letters, digits and `. _ @ -`.
Do not let your users choose run ids: reusing the id of an earlier run replays that run's journal.

## 5. Waiting for a person

When the workflow reaches `human_prompt` (or an `approve:` agent) the run returns `Suspended`. It is saved, the executor is shut down, and nothing waits in memory, so it survives a restart.

Learn that a question was asked by either of:

```java
@Component
class Notify {
    @EventListener
    void asked(HumanQuestionEvent e) { /* e.runId(), e.stepId(), e.prompt(), e.choices(): tell someone */ }
}
```

or declare a `QuestionListener` bean. Then answer from anywhere (a web request, a chat message):

```java
@PostMapping("/runs/{runId}/answer")
AnswerResult answer(@PathVariable String runId, @RequestParam String step, @RequestParam String text, Principal who) {
    // your own check that `who` may answer this run goes here
    return loom.answer(runId, step, text, who.getName());
}
```

The run carries on from its journal: steps that already ran (and cost money or sent something) are not repeated. `AnswerResult.recorded()` is false, with a message, if the run is not waiting there or was already answered.
`loom.runs()` and `loom.run(id)` list the records (status, workflow, what it is waiting for).

## 6. Persistence

| `llm4j.loom.persistence` | Use it for |
|---|---|
| `none` (default) | a workflow that never waits. Starting a script that can wait (`human_prompt`, `approve:`, `sleep`, `rate_limits`, a windowed budget, `decision`/`ladder`) fails at start-up naming the line |
| `file` + `dir` | one application process. Runs sit in the folder layout `weave` uses, so `weave timeline <dir>/runs/<id>` reads them |
| `jdbc` | several application instances, or when you already have a database |

For `jdbc` the starter uses your `DataSource` (name one with `llm4j.loom.datasource` if there are several, otherwise exactly one must exist).
`llm4j.loom.initialize-schema` is `embedded` by default (create the tables on H2 and friends), `always` or `never`; the tables are created by `LoomHost.createSchema(dataSource)` if you prefer to run it yourself.
`allow-non-durable: true` lets a script that can wait run with `none`, on the understanding that a run which would wait then fails instead of suspending.

## 7. Schedules and sleeping runs

If the script has `schedule` blocks or `sleep` steps, turn the trigger runner on:

```yaml
llm4j:
  loom:
    persistence: file
    dir: var/loom
    triggers:
      enabled: true
      poll-interval: 15s
```

It needs persistence. It fires what is due, wakes sleeping runs, and publishes a `TriggerFiredEvent` for each. Several application instances can share one `jdbc` store; each trigger fires once. It stops cleanly when the application closes.

## 8. Running for a particular user

If a workflow tool should act with the signed-in user's own token (`auth_value: invoker.token` in the script), pass it with the request:

```java
loom.run(RunRequest.of("Support", Map.of("ticket_text", ticket))
        .as(InvokerCredentials.of(Map.of("token", "Bearer " + userToken))));
```

The credentials are used for that run only and are written nowhere: not the journal, not the run record, not the database. A run that needs them and was not given them is refused before anything runs. To resume such a run later, call `loom.resume(runId, credentials)` with them again.

## 9. Your own tasks and tools

- A `Task` bean (a Java class a workflow calls with `run MyTask(...)`) is registered by name; two with one name stop the application starting. `llm4j.tasks.discover=false` turns discovery off.
- A `Tool` bean, a built-in (`llm4j.tools.builtin: [calculator]`) or a guarded tool (`llm4j.tools.custom.<name>.kind: webhook|email|http|file|shell|sql`) is available to the workflow once its `.loot` file has a reach line for it.
  A tool that runs programs (`shell`) must say `options.unattended: true`, because every agent can use a registered tool.
- A `LoomExecutorCustomizer` bean runs on every new executor just before it initialises: set a `HumanInterface`, an audit logger or anything else the library exposes. Declare an `LLMClientFactory` bean to replace how models are made. `PriceTable`, `PromptRegistry`, `AuditLogger`, `Clock` beans are passed through.
- Per-run limits: `llm4j.loom.budget.tokens`, `.calls`, `.cost` (cost needs prices: `llm4j.pricing.*`).

## 10. Concurrency and shutdown

`llm4j.loom.max-concurrent-runs` (default 32) bounds `start`; one more throws `TooManyRuns`. `llm4j.loom.executor` is `platform` (threads named `llm4j-loom-*`), `virtual-threads` (Java 21) or `application` (your `applicationTaskExecutor`).
When the application stops, running runs are asked to wrap up for `llm4j.loom.shutdown-grace` (default 30s); a run still going then is suspended if there is a store, or cancelled if not.

## 11. Watching runs

Add `loom-inspect` and set `llm4j.observe.enabled: true` (port 8787 on loopback by default) for the live run page. A non-loopback `bind` needs the `LOOM_INSPECT_ADMIN_PASSWORD` secret first.

## Things that will stop the application starting

| Message mentions | Cause |
|---|---|
| `ai-agent4j-loom` | Loom is not on the class path |
| `llm4j.loom.script … does not exist` | wrong location, or no `classpath:`/`file:` prefix |
| `human_prompt` + `persistence` | the script can wait and `persistence` is `none` |
| `GEMINI_API_KEY` (or another) | a model's key is not in the secret store or environment |
| `the security audit … found problems` | the script fails `weave audit` at `fail-on`; fix the script (the report is in the message) |
| a tool name | the script uses a tool nobody registered |
| `two tools are named …` | a `Tool` bean and a `custom` tool (or two tasks) share a name |

## The same thing without Spring

```java
LoomScript script = new LoomLoader().load("workflows/support.loom");
LoomHost host = LoomHost.builder(script, new ToolRegistry(), new DefaultLLMClientFactory(System::getenv, null))
        .persistence(Persistence.file(Path.of("var/loom")))
        .questionListener((run, step, prompt, choices) -> notifyTheTeam(run, prompt))
        .build();
RunOutcome out = host.run(RunRequest.of("Support", Map.of("ticket_text", text)));
host.answer(runId, stepId, "yes", "asha");
host.startTriggers(Duration.ofSeconds(15), null, Duration.ofMinutes(5), null);
```

The starter is this, plus reading your `application.yml`. See [the Loom guide](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md) for the language itself.
