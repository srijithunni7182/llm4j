# Agents as objects: why llm4j is written the Java way

[← Back to the README](../README.md)

## Java deserves first-class AI

For twenty-five years Java has run the systems that can't go down: banks, airlines, telecoms, the
back offices of the world. Java developers have strong reasons to trust it:

- **Types** catch mistakes before the program runs.
- **Interfaces** keep contracts honest.
- **Objects** own their state and their behaviour.
- **The compiler is the first reviewer**, the IDE refactors a thousand call sites safely, and the JVM
  runs for months without a restart.

Then AI arrived, and the ecosystem went mostly to Python: dictionaries passed between untyped
functions, prompts in string templates, and agents you can't unit test.

**llm4j is the other path.** It is a complete AI stack written from the ground up in idiomatic Java,
with no vendor SDKs, where an agent is as ordinary as a `PaymentService`. It's no less capable; it's
built the way Java developers already build everything else.

---

## An agent is just an object

In llm4j, every part of an AI system maps onto something a Java developer already knows:

| AI concept | In llm4j, it's… |
|---|---|
| A language model | An `LLMClient` interface. Gemini, Sarvam, Ollama and Claude sit behind [one contract](../ai-agent4j/wiki/Providers-and-the-Uniform-Contract.md), so switching is one line. |
| A tool the model can use | A class that implements `Tool`, with a name, a description and an `execute` method. |
| A prompt | A versioned resource in a [`PromptRegistry`](../ai-agent4j/wiki/Prompt-Registry-Guide.md), or a Markdown [skill](../ai-agent4j/wiki/Agent-Skills-Guide.md) on the classpath. Not a string buried in code. |
| An agent | An immutable object built with a builder, from a client, tools, skills, memory and a budget. |
| A risky action | `requiresApproval(args)` on the tool, and an `ApprovalCallback` that a person answers. |
| A spending limit | A `Budget` value object, checked before every model call. |
| Something going wrong | A typed exception: `AuthenticationException`, `RateLimitException` with the exact reset time, `ContentBlockedException`. |
| A test | An AssertJ assertion, in JUnit, in your normal build. |

Here is what that looks like. A tool is a class:

```java
public class RefundTool implements Tool {
    private final Payments payments;

    public RefundTool(Payments payments) { this.payments = payments; }

    @Override public String getName()        { return "refund"; }
    @Override public String getDescription() { return "Refund an order. Args: orderId, amount"; }

    @Override
    public String execute(Map<String, Object> args) {
        return payments.refund((String) args.get("orderId"), ((Number) args.get("amount")).doubleValue());
    }

    @Override
    public boolean requiresApproval(Map<String, Object> args) {
        return ((Number) args.get("amount")).doubleValue() > 100;     // big refunds need a person
    }
}
```

An agent is composed like any other object:

```java
ReActAgent support = ReActAgent.builder()
        .llmClient(client)                                            // any provider
        .addSkill(AgentSkill.fromClasspath("skills/refund-policy.md"))  // domain knowledge, in Markdown
        .addTool(new RefundTool(payments))
        .approvalCallback((tool, args, plan) -> supervisor.confirm(tool, args))
        .budget(Budget.builder().tokens(20_000).build())              // it cannot overspend
        .build();

AgentResult result = support.run("Customer 42 was charged twice for order A-17.");
```

And it's tested like any other object, with [eval4j](../eval4j/):

```java
assertThat(support.run(question))
        .usesTool("refund")
        .completedSuccessfully()
        .is(presets.taskCompletion(question));    // an LLM judge, as an AssertJ Condition
```

No framework magic, no annotation processors, no hidden global state. Just classes, interfaces and a
builder, and a compiler that has your back.

