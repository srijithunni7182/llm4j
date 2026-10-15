# Steering, Streaming, Tool Hooks and Delegation Limits

Four ways to stay in control of an agent that is running.

## Steer, pause or cancel a run: `RunHandle`

`run` blocks the calling thread, so the control comes from a handle you create first and keep:

```java
RunHandle handle = new RunHandle();
// another thread (a UI, a request handler, a chat listener):
handle.steer("Skip the integration tests");   // the model reads it before its next call
handle.pause();                               // waits at the next checkpoint
handle.resume();
handle.cancel();                              // ends the run; result.isCancelled()

AgentResult result = agent.run("Fix the build", handle);   // on the agent's own thread
```

The loop looks at the handle before every model call and between the tool calls of one turn. A steer arrives as a user message ("Message from the user: ...") after the tool results, so each tool call stays next to its result. A handle controls one run at a time, and a cancelled handle cannot be used again. `run(question)` without a handle is unchanged.

Listeners hear `onSteered`, `onPaused`, `onResumed` and `onCancelled`.

## Watch the model work: streaming and reasoning

By default a model call is whole, so you only see its text when it is finished. Turn on streaming (native tool calling) to see it as it is written, and to let a steer or cancel **end the call in progress** at once, even if the model is silent:

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client).addTool(...)
        .streaming(true)         // opt in
        .reasoning(true)         // optional: also ask the model to show its reasoning
        .addListener(new AgentEventListener() {
            public void onReasoningDelta(String piece) { ui.thinking(piece); }
            public void onTextDelta(String piece) { ui.text(piece); }
            public void onToolCallStarted(String tool) { ui.badge(tool); }
            ...
        })
        .build();
```

- **Reasoning** is what the provider will show: for Claude a readable summary of its thinking (never the raw chain of thought), for Gemini the thought summaries. Models that cannot show it ignore the option. It is never part of the answer.
- When a steer cuts a call short, what the model had already said stays in the conversation; half a tool call does not run. A call whose arguments were cut off or are not valid JSON is not run either, and the model is told to write it again.
- A streamed call is billed for what was generated before it was cut off, and a stream that fails half-way is not retried.
- At the provider level: `LLMRequest.builder().reasoning(true)` and `LLMResponse.getReasoning()` / `getToolCallDelta()` on streamed chunks. Joining the `content` of the chunks still gives the answer only.

## Check tool calls: `ToolHook` and argument validation

Models often write arguments a tool cannot use. Check them before anything runs:

```java
ReActAgent.builder()
        .validateToolArguments(true)      // required / type / enum, against the tool's own schema
        .addToolHook(new ToolHook() {
            public Decision beforeCall(String tool, Map<String, Object> args) {
                if (args.get("limit") instanceof Integer n && n > 100)
                    return Decision.reject("limit must be at most 100; you sent " + n);   // the model reads this and tries again
                return Decision.allow();      // or Decision.replaceArguments(fixed)
            }
            public String afterCall(String tool, Map<String, Object> args, String result) {
                return result.replaceAll("sk-[A-Z0-9-]+", "[KEY]");    // what the model sees (and the audit log records)
            }
        })
```

Checks run before approval, so a person approving a call sees the arguments as the hooks left them. A rejected call is a step with outcome `REJECTED_BY_HOOK` (category `INVALID_ARGUMENTS` unless the hook says otherwise), and listeners hear `onToolRejected`. A hook that throws fails closed: the call does not run, or the result is withheld.

## Bound delegation: `DelegateTaskTool`

```java
DelegateTaskTool.builder(registry, subAgentClient)
        .maxDepth(2)                                  // default: helpers may have helpers, no deeper
        .grantedTools(List.of("search", "calculator")) // the tools this manager holds; it cannot hand out others
        .approvalCallback(approvals)                  // otherwise tools needing approval stay blocked for sub-agents
        .build();
```

A helper at the limit has no `delegate_task`, and naming it in `requiredTools` does not get a deeper copy. A request for a tool outside the grant is refused with nothing started, so the manager can ask again.
