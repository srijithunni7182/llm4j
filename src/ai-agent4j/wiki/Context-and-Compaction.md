# Context and compaction

An agent's prompt grows with every step: each thought, tool call and result is added and sent again. Sooner or
later it no longer fits the model's **context window**. This page is about keeping it small enough, on purpose, with
the parts you choose: **how much room is left**, **what to do about it**, and **what must never be touched**.

> **Status: in progress.** The building blocks on this page are available now and work on their own: the
> strategies, the window catalogue, the policy and the extension point. They are **not yet connected to
> `ReActAgent`**: an agent still sends everything it has. Connecting them (so an agent measures its context, compacts
> before the limit and recovers from a "prompt too long" error) is the next step of the work and will be documented
> here as it lands. The design is in [`docs/specs/01-context-management`](../../../docs/specs/01-context-management/DESIGN.md).

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
9. [Guarantees](#9-guarantees)
10. [What is coming](#10-what-is-coming)

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

A strategy that asks a model to **summarise** the oldest part is planned (see [What is coming](#10-what-is-coming)).

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

## 9. Guarantees

- The conversation you pass in is never modified.
- Protected content and tool-call pairs are never broken; the result is never larger than the input.
- No shared mutable state: strategies and the catalogue can be shared between threads and agents.
- No new dependency.
- Every built-in is checked by the same contract test (a few thousand generated conversations, with several recent-window sizes),
  and so is the class in section 5.

## 10. What is coming

In this order, each with its own tests and documentation:

1. An agent that **measures its context** before each model call and reports it to its listeners.
2. A manager that **compacts automatically** before the limit, in both the tool-calling and the text-protocol loops, with guards
   (a gain check, a limit on repeats, and a compactor that fails never fails the run).
3. A `summarize(client)` strategy, with a fallback to `dropOldest` and its cost counted.
4. Recovery from a provider's **"prompt too long"** error: compact harder once and retry once.
5. A token-based window for `ConversationHistory`.
6. Models seeded in the window catalogue, verified against provider documentation.

See also: [ReAct Agent Guide](ReAct-Agent-Guide.md) · [Budgets and Rate Limits](Budgets-and-Rate-Limits.md) ·
[Memory and Persistence](Memory-and-Persistence.md)
