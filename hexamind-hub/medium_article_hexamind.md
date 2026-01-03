# The Day My AI Agents Started Arguing in a Boardroom

*Srijith Unni* | *5 min read* | *Jan 03, 2026*

---

If you read my previous article, you might remember that I built a Google Gemini Agent in Java. I was very proud of it. It could do math, it could say hello, and it didn't crash often. I felt like a proud parent whose child had just learned to walk.

But then I realized a hard truth about software: **A library that sits on a shelf is useless.**

You can write the cleanest Java code in the world, have 100% unit test coverage, and a beautiful README. But until you use that library to build something complex, messy, and real, you don't know if it actually works.
`gemini-react-java` was like a pristine engine block. It looked great on the stand. But I needed to know what would happen if I put it in a car and drove it at 200 km/h.

I didn't need a demo. I needed a stress test.
I needed to build an application that would push every edge case, trigger every concurrency bug, and expose every flaw in my design.

This is the story of **Hexamind Hub**, and how building a "Digital Boardroom" of arguing agents was the only way to make my library production-ready.

## Why We Needed a Boardroom (The Stress Test)

You see, writing a library is easy. Proving it works is hard.
I realized that a simple "Hello, World" or a single chatbot wasn't enough to test the limits of `gemini-react-java`. I needed chaos. I needed concurrency. I needed a situation where multiple agents would try to talk, think, and act at the exact same millisecond.
Hexamind Hub wasn't just a cool demo; it was the crucible. It was the gym where my library got verified. Only by making agents argue with each other did I discover the real bugs—the race conditions, the context window overflows, and the hallucinations that only appear when an AI gets confused by another AI's logic.

## Enter the Boardroom: Hexamind Hub

## The Perfect Test Subject: A Boardroom of Rivals

To properly test a library, you need variability. A single chatbot is predictable. A debate is not.
So I built **Hexamind Hub**—a multi-agent boardroom designed to generate conflict. I created specific personas not because they were fun, but because they stress-tested different aspects of the `gemini-react-java` architecture:

* **Alex (Technical)** tests the *Code Execution* and *search* capabilities.
* **Sasha (Creative)** tests the *Temperature* and *Creativity* settings.
* **Rahul (The Skeptic)** exists to find logical flaws, testing the *Reasoning Loop* itself.

If the library could handle these three arguing simultaneously without crashing the WebSocket or confusing the context windows, I knew it could handle anything.

But making them work together wasn't just about prompt engineering. It was about solving real distributed system problems.

## The "Thinking" Problem (When AI Thinks Too Loud)

One morning, everything broke. The `ReActAgent` class, which had been stable for weeks, started throwing parsing errors.
The logs showed something bizarre. We expected standard JSON:

```json
{
  "thought": "I need to check the weather",
  "action": "WeatherTool",
  "actionInput": "London"
}
```

Instead, Gemini 2.5 started sending us its inner monologue *inside* the response, but outside the JSON structure, or worse, mixed in with "thinking tokens" that looked like this:

```text
Message: I am analyzing the user request... 
<thinking>
Wait, should I check London or Greater London? 
Let's stick to the city center.
</thinking>
```

Our poor JSON parser choked. It was like trying to parse a strict XML file that suddenly contained a handwritten sticky note. We had to rewrite the `ReActAgent` response handler to regex-match purely for `Thought:`, `Action:`, and `Action Input:` patterns, ruthlessly stripping out the model's "stream of consciousness" so the application wouldn't crash.

## The Silent WebSocket

Real-time collaboration needs real-time feedback. You can't have Rahul arguing with Sasha and wait 2 minutes for the page to refresh.

We implemented Spring WebSockets with `SimpMessagingTemplate` to broadcast the "Neural Metrics"—live stats of the agents' brain activity. But for two days, the UI was a ghost town. The backend logs said `Sending message to /topic/updates`, but the frontend showed nothing.

It turned out to be the classic "Silent Failure." The messages were too large. When agents started debating complex topics, their payloads exceeded the default 512KB WebSocket limit. The server wasn't throwing an error; it was just quietly dropping the packets.
We had to tune the buffer size:

```java
registry.setApplicationDestinationPrefixes("/app");
registry.setUserDestinationPrefix("/user");
transportRegistration.setMessageSizeLimit(1024 * 1024); // Give them space to talk!
```

Suddenly, the dashboard lit up. It was alive.

## The Dependency Hell (Jackson's Revenge)

No Java project is complete without a `NoSuchMethodError`.
When we integrated the agent library into the main web app, the server refused to start.
`java.lang.NoSuchMethodError: com.fasterxml.jackson.core.JsonGenerator.writeStartObject`

It’s the error that makes grown developers cry. One library wanted Jackson 2.15. Another wanted 2.13. The classpath was a warzone. We had to forcefully exclude the transitive dependencies in Maven and pin everything to the latest version. It wasn't the AI that was hard to manage; it was the build tool.

## The Shared Brain: Why Stateless Agents Fail

Hexamind Hub revealed a critical flaw in my initial library design: **Amneisa**.
In a complex application, agents cannot be stateless. When Alex found a technical article, he read it. But because there was no shared state, Jordan had to search for the *exact same article* five seconds later. It was inefficient and expensive.

Real-world applications don't just need "chatbots"; they need "systems of record."
This forced us to upgrade `gemini-react-java` to support **RAG (Retrieval Augmented Generation)** and **Knowledge Graphs**.

* **RAG is the Memory**: It remembers what was said five minutes ago, so you don't pay for the same API tokens twice.
* **Knowledge Graph is the Understanding**: If Alex says "Java is robust," the graph notes a persistent connection: `Java --[has property]--> Robust`.

Now, when Rahul asks "Is the tech stack reliable?", he doesn't hit Google. He queries the persistent Memory Store we built into the library. Hexamind proved that without memory, an agent is just a party trick.

## Conclusion: The Feedback Loop

We started with a simple Java library. We ended up with a digital parliament that debates, researches, and remembers.
But most importantly, **building Hexamind Hub is what made `gemini-react-java` production-ready.**

Without this stress test, the library would still be a toy. Because of Hexamind, we were forced to implement:

1. **Robust "Thought" Parsing**: To handle the verbose, "thinking" models of 2026.
2. **Infinite Loop Detection**: So agents don't get stuck asking the same question forever.
3. **Prompt Registry**: A disciplined way to manage the complex personalities of 6 distinct agents.
4. **Tooling Architecture**: Adding **Web Search** and **Time Awareness** so agents aren't blind and timeless.
5. **Resiliency**: Fixing the `Jackson` dependency hell that only appears when you integrate with real-world frameworks like Spring Boot.

The lesson? Building with AI is 10% magic and 90% engineering. You can't just write a library; you have to build a product *with* it to find out where it breaks.

If you want to see Rahul argue with Sasha, or if you want to use the battle-tested library that survived them, check out the code: [https://github.com/srijithunni7182/llm4j](https://github.com/srijithunni7182/llm4j)

And if you build a team of agents, just a warning: don't let Rahul talk too much. He can be very convincing.
