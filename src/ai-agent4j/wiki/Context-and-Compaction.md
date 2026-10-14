# Context and compaction

An agent's prompt grows with every step: each thought, tool call and result is added and sent again. Sooner or
later it no longer fits the model's **context window**. This page is about keeping it small enough, on purpose, with
the parts you choose: **how much room is left**, **what to do about it**, and **what must never be touched**.

> **Status: in progress.** The strategies, the window catalogue and the policy work on their own (sections 2 to 8), and an agent now
> measures its context before every model call, reports it to its listeners, and compacts automatically before the limit, **with native
> tool calling and with the text protocol** (sections 9 to 11). Still to come, in this order: a `summarize` strategy, recovery from a
> provider's "prompt too long" error, and a token window for `ConversationHistory` (section 13). The design is in
> [`docs/specs/01-context-management`](../../../docs/specs/01-context-management/DESIGN.md).

Everything here is opt-in and changes nothing for code that does not use it.

---

## Contents

1. [The idea](#1-the-idea)
2. [Use a strategy](#2-use-a-strategy)
3. [The built-in strategies](#3-the-built-in-strategies)
4. [What is always protected](#4-what-is-always-protected)
5. [Write your own](#5-write-your-own)
6. [Find a strategy by name](#6-find-a-strategy-by-name)
7. [Know the window](#7-know-the-window)
8. [The policy](#8-the-policy)
9. [Use it in an agent](#9-use-it-in-an-agent)
10. [Watch what it does](#10-watch-what-it-does)
11. [The guards](#11-the-guards)
12. [Guarantees](#12-guarantees)
13. [What is coming](#13-what-is-coming)

---

## 1. The idea

A **compactor** is a function from a conversation (a `List<Message>`) and a request (a target size and what to protect)
to a smaller conversation. It does not touch the agent, a store or a listener: it only returns the new messages, with
numbers about what it did. That makes every strategy easy to test, to combine and to replace.

## 2. Use a strategy

By hand, on any conversation:

```java
CompactionResult result = ContextCompactors.dropOldest()
        .compact(messages, CompactionRequest.of(4_000));
List<Message> smaller = result.messages();
System.out.println(result.strategy() + ": " + result.tokensBefore() + " -> " + result.tokensAfter()
        + " tokens, " + result.droppedMessages() + " messages removed");
```

Strategies combine. This one first shortens large tool results (the usual cause of growth) and, only if that is not enough,
drops the oldest exchanges:

```java
ContextCompactor compactor = ContextCompactors.chain(
        ContextCompactors.trimToolResults(2_000),   // first shorten big tool results
        ContextCompactors.dropOldest());            // then drop the oldest exchanges if still too big
CompactionResult result = compactor.compact(messages, CompactionRequest.of(3_000));
```

Every strategy returns a `CompactionResult`: the new `messages()`, the `strategy`, an `outcome` (`COMPACTED`, `NO_GAIN` or
`SKIPPED`), counts of messages and estimated tokens before and after, and how many messages were removed or replaced. Sizes are
estimates made with the same `TokenEstimator` budgets use (about four characters to a token, plus a margin).

## 3. The built-in strategies

| Factory | Name | What it does | Model call |
|---|---|---|---|
| `ContextCompactors.none()` | `none` | Returns the conversation as it is | no |
| `ContextCompactors.dropOldest()` | `drop_oldest` | Removes whole exchanges from the oldest end of the unprotected middle until the target is reached, and leaves one marker: *"[12 earlier messages were removed to save space.]"*. A second pass merges into that marker rather than adding another | no |
| `ContextCompactors.trimToolResults(maxChars)` | `trim_tool_results` | Shortens tool results longer than `maxChars` (at least 40), oldest first, keeping the start and the end of each with a note of how much was omitted, and stops as soon as the target is reached. A result already shortened is not shortened again | no |
| `ContextCompactors.chain(a, b, …)` | `chain` | Applies strategies in order and stops as soon as the target is reached | as its parts |

A strategy that asks a model to **summarise** the oldest part is planned (see [What is coming](#13-what-is-coming)).

## 4. What is always protected

Every strategy, yours included, must leave these as they are:

- the leading **system** messages and the **first user message** (the task);
- the **last N messages** (`keepRecent`, default 4), widened so that an exchange is never cut in half;
- any message the request's `pinned` test accepts.

Mark a message as pinned where you create it, or give the request a test of your own:

```java
Message budget = Messages.pinned(Message.user("The budget is 500 and must not change."));
CompactionRequest request = CompactionRequest.builder(3_000)
        .keepRecent(6)                           // the last 6 messages are never touched
        .pinned(Messages::isPinned)              // nor is anything pinned
        .build();
```

Also kept, whatever the strategy: **a tool call and the results that answer it stay together** (never a result without
its call, nor a call without its results), and **the order of surviving messages is unchanged**.

## 5. Write your own

`ContextCompactor` has one method, so a lambda is enough:

```java
ContextCompactor keepTheTaskOnly = (msgs, request) ->
        CompactionResult.of("keep_task_only", msgs, msgs.subList(0, 2), request);
```

A class can do more, and should say what it is called. This one cuts long assistant thoughts and leaves everything
else alone; it respects `keepRecent` and pinning, and marks what it changes:

```java
/** Cuts long assistant thoughts to 300 characters. Tool calls, the task and recent messages are left alone. */
static final class ShortenThoughts implements ContextCompactor {
    @Override
    public CompactionResult compact(List<Message> messages, CompactionRequest request) {
        int protectedFrom = Math.max(0, messages.size() - request.keepRecent());   // the last messages stay as they are
        List<Message> out = new ArrayList<>(messages);
        for (int i = 0; i < protectedFrom; i++) {
            Message m = messages.get(i);
            if (m.getRole() == Message.Role.ASSISTANT && !request.pinned().test(m) && m.getContent().length() > 300) {
                String cut = m.getContent().substring(0, 300) + " …";
                out.set(i, Messages.marked(m.withContent(cut), name()));   // mark what you change
            }
        }
        return CompactionResult.of(name(), messages, out, request);          // a result that is not smaller comes back as NO_GAIN
    }

    @Override
    public String name() {
        return "shorten_thoughts";
    }
}
```

Rules to follow (they are listed on the `ContextCompactor` Javadoc):

1. **Do not modify the input list**; return a new one.
2. **Leave protected content alone** (section 4).
3. **Keep tool calls and their results together.**
4. **Keep the order** of what survives.
5. **Never return something bigger.** Build the result with `CompactionResult.of(...)`: if your output is not smaller than
   the input it returns the input with outcome `NO_GAIN`, so you cannot make a prompt bigger by mistake.
6. **Be deterministic** unless you call a model.
7. **Be idempotent in effect:** compacting your own output again should gain (almost) nothing.
8. **Mark what you create or change** with `Messages.marked(message, name())`, so a message can be recognised as a product of compaction.

## 6. Find a strategy by name

A configuration file cannot hold a Java object, so strategies are also found by name. The built-ins register themselves
the same way yours would:

```java
ContextCompactor c = ContextCompactors.byName("trim_tool_results", Map.of("max_chars", "1000"));
ContextCompactor chain = ContextCompactors.byName("chain",
        Map.of("strategies", "trim_tool_results, drop_oldest"));
```

To make your own findable, implement `ContextCompactorProvider` (a name and a `create(options, context)` method) and list it in
`META-INF/services/io.github.llm4j.context.ContextCompactorProvider` in your jar. An unknown name fails with the nearest known name
suggested and the list of known ones. `ContextCompactors.names()` lists what is available. Options arrive as text; reject the
ones you do not know.

| Name | Options |
|---|---|
| `none`, `drop_oldest` | none |
| `trim_tool_results` | `max_chars` (default 2000) |
| `chain` | `strategies`: comma-separated names (required) |

## 7. Know the window

To compact before the limit you need to know the limit. `ContextWindows` is a small catalogue from model name to window, in the
style of the `PriceTable`. **Nothing ships in it**, because windows change; you supply your own file:

```
# windows.properties: model = window [/ tokens kept for the reply]
acme/big-model = 200000 / 8192
my-local-model = 32000
```

`windowsFile` is the `Path` of that file:

```java
ContextWindows windows = ContextWindows.load(windowsFile)
        .with("acme/other-model", ContextWindow.of(128_000).reservingForOutput(4_096));
ContextWindow window = windows.window("acme/big-model").orElseThrow();   // usableInput() = 191,808
```

A model is looked up by its id, then by the id without its provider prefix, ignoring case. An unknown model has **no window**;
nothing is guessed. A malformed line is rejected with its line number.

## 8. The policy

`ContextPolicy` says when to compact and how far, and carries the protections:

```java
ContextPolicy policy = ContextPolicy.builder()
        .window(ContextWindow.of(200_000).reservingForOutput(8_000))
        .triggerAt(0.75)       // compact when the next prompt would pass 75 % of the usable window
        .targetAt(0.40)        // and bring it down to about 40 %
        .keepRecent(6)
        .maxCompactions(8)
        .build();
```

| Setting | Default | Meaning |
|---|---|---|
| `window` | none | The model's window. Without one nothing compacts automatically |
| `triggerAt` | 0.80 | Compact when the next prompt would pass this share of the usable window |
| `targetAt` | 0.50 | Bring it down to about this share (must be below `triggerAt`) |
| `keepRecent` | 4 | The last messages that are never touched |
| `maxCompactions` | 8 | Per run; after that compaction stops |
| `minGain` | 0.10 | A result must be at least this much smaller to be used |
| `pinned` | none | Messages that are never removed or changed |
| `onOverflow` | `RETRY_ONCE` | What to do when the provider rejects a prompt as too long |

`usableInput()` is the window less the tokens reserved for the reply. Invalid combinations are rejected when the policy is built,
with a message naming the setting.

## 9. Use it in an agent

Give the agent a window and a strategy:

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(search)
        .contextWindow(4_000)          // or: .budgetModel("acme/big-model").contextWindows(windows)
        .compaction(ContextCompactors.chain(
                ContextCompactors.trimToolResults(2_000),
                ContextCompactors.dropOldest()))
        .build();
```

`client` is your `LLMClient` and `search` one of your tools. The window can come from three places, in this order: the `window` of a
`ContextPolicy` you pass with `.contextPolicy(policy)`, the `.contextWindow(tokens)` shorthand, or a catalogue (section 7) looked up by
the model name you set with `.budgetModel(...)`, the name budgets already use for prices. **Without a window nothing is compacted
automatically**, because the agent cannot know where the limit is; it still reports its context use.

Before every model call the agent works out how big the next prompt will be: the provider's own prompt count from the previous call plus
an estimate of what was added since, or, on the first call (and right after a compaction), an estimate of the whole prompt including the
tool definitions. If that is **above `triggerAt` of the usable window**, it asks the strategy to bring the conversation down to
`targetAt`, and sends the compacted conversation. The saving lasts: later calls build on the compacted conversation, not the original.

This works in both of the agent's loops: **native tool calling** (the default for models that support it) and the **text protocol** (for
models that do not, or when you ask for it with `toolCalling(ToolCalling.TEXT)`). An agent with no compaction strategy, no window and no
listener sends exactly what it always has; tests pin this request by request, in both loops.

### In the text protocol

The text protocol sends the model one growing block of text each iteration: the question, then a *Plan / Action / Action Input / Observation*
block per step. The agent keeps those steps as messages (a step is a call and its result, the same shape as in native tool calling), so
every strategy in this page works on them unchanged. In particular `trimToolResults` shortens the *Observation* of old steps in place, and
`dropOldest` removes whole steps. When nothing has been compacted the text is exactly what it always was. After a compaction the model
reads, for example:

```text
Question: Summarise the pages.
Earlier steps: [8 earlier messages were removed to save space.]
Plan: Look at page 4.
Action: fetch
Action Input: {"page": 4}
Observation: page 5 says blah blah blah blah blah blah blah blah blah blah blah blah
```

The question is always first; removed steps are replaced by one *Earlier steps* line; the latest steps are intact. A reply that named no
usable action and a reply that could not be handled are turns too, and can be removed like any other. The system prompt (which lists the
tools) and any conversation history count against the window along with the scratchpad.

## 10. Watch what it does

Two new methods on `AgentEventListener`, both optional (existing listeners keep working unchanged):

```java
AgentEventListener listener = new AgentEventListener() {
    @Override public void onThought(String thought) {}
    @Override public void onAction(String tool, String input) {}
    @Override public void onObservation(String observation) {}

    @Override
    public void onContextUsage(ContextUsage usage) {
        usage.fraction().ifPresent(f -> lines.add(String.format("context %.0f%% full (%s)", f * 100, usage.source())));
    }

    @Override
    public void onCompaction(CompactionEvent e) {
        lines.add(e.result().strategy() + " " + e.result().outcome() + ": " + e.result().tokensBefore()
                + " -> " + e.result().tokensAfter() + " tokens" + (e.failure() != null ? " (" + e.failure() + ")" : ""));
    }
};
```

- **`onContextUsage`** fires before every model call: the prompt size in tokens, whether it is `REPORTED` (the provider's count plus an
  estimate of what was added since) or `ESTIMATED`, the window, and `fraction()` of the usable window, which is empty when the window is not
  known and can be above 1.0.
- **`onCompaction`** fires after every attempt: the `CompactionResult` (strategy, outcome, sizes before and after, messages removed), the
  reason (`THRESHOLD`), the iteration, the usage before and after, and `failure` when the strategy threw. A result that was not used, a
  failure and the notice that compaction has stopped are all reported, so nothing happens silently.

A listener that throws is logged and ignored; it cannot fail a run.

## 11. The guards

Compaction must never make a run worse. The agent enforces, whatever strategy you give it:

| Guard | What it does |
|---|---|
| Gain check | A result that is not at least `minGain` (10 %) smaller, **measured by the agent, not taken from the strategy's own numbers**, is discarded and the conversation is used as it was |
| Repeat limit | After `maxCompactions` (8) in one run, or two attempts in a row that gained nothing or failed, compaction stops for the rest of the run, with one `onCompaction` notice saying why. Usage keeps being reported |
| Fail open | A strategy that throws never fails the run: the failure is reported and the conversation is used as it was |

One manager serves one run and holds nothing shared, so many agents can use the same strategy instance at once.

## 12. Guarantees

- The conversation you pass in is never modified.
- Protected content and tool-call pairs are never broken; the result is never larger than the input.
- No shared mutable state: strategies and the catalogue can be shared between threads and agents.
- No new dependency.
- An agent that is not given a strategy, a window or a listener sends exactly the requests it sent before this feature existed.
- Every built-in is checked by the same contract test (a few thousand generated conversations, with several recent-window sizes),
  and so is the class in section 5.

## 13. What is coming

In this order, each with its own tests and documentation:

1. A `summarize(client)` strategy, with a fallback to `dropOldest` and its cost counted.
2. Recovery from a provider's **"prompt too long"** error: compact harder once and retry once, and refuse a call that cannot fit.
3. A token-based window for `ConversationHistory`.
4. Models seeded in the window catalogue, verified against provider documentation.

See also: [ReAct Agent Guide](ReAct-Agent-Guide.md) · [Budgets and Rate Limits](Budgets-and-Rate-Limits.md) ·
[Memory and Persistence](Memory-and-Persistence.md)
