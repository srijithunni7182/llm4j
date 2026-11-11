# Checking llm4j against other A2A implementations

The mock agent in this module is our own reading of the specification, so it cannot catch a misreading we share with it. These three small programs check llm4j against code we did not write. **They are not part of the build and need the network**: they fetch third-party repositories and packages, so run them by hand.

| Program | What it checks |
|---|---|
| `ThirdPartyCheck.java` | llm4j's client half against a third-party agent: reading its card, the endpoint guard refusing a plain-`http` loopback card, a send, a get, the crash rule's lookup by message id in the agent's task list, and the error mappings |
| `ThirdPartyLoom.java` | A Loom workflow with a `remote` delegating to that agent: the run suspends while the task works and completes on resume |
| `TckServer.java` | llm4j's server half: `A2AHandler` and an exposed Loom workflow over HTTP on loopback, for the A2A project's compatibility kit |

## Steps (as run on 2026-11-11)

You need Java 17, Python 3.11 or later and [uv](https://github.com/astral-sh/uv). Do everything in a scratch folder, and treat what you download as untrusted.

1. **The agent.** Clone `a2aproject/a2a-samples`, make a virtual environment, install `samples/python/agents/helloworld/requirements.txt` (it pins `a2a-sdk==1.1.0`) and start `__main__.py` from outside that folder. It listens on `http://127.0.0.1:9999`.
2. **The kit.** Clone `a2aproject/a2a-tck`. Its newest commits ask for Python 3.15, which may not be installable; commit `70f6f73` is the last that accepts earlier versions. Create a virtual environment and `uv pip install -e .`.
3. **Compile** the three programs against the classpath of any module that depends on `ai-agent4j` and `ai-agent4j-loom` (for example `mvn -Pextended -pl src/examples/a2a-starter dependency:build-classpath`), after `mvn -DskipTests install` of those two modules.
4. **Client half:** `java ThirdPartyCheck http://127.0.0.1:9999` and `java ThirdPartyLoom http://127.0.0.1:9999`.
5. **Server half:** `java TckServer 9998`, then in the kit's folder `python run_tck.py --sut-host http://127.0.0.1:9998 --transport jsonrpc`. Run the kit against the helloworld agent too, to see which tests it fails as well.

## What it found (2026-11-11)

- **Client half:** all five checks passed against the helloworld agent (a2a-sdk 1.1.0), and the Loom run completed.
- **Server half, first run:** 50 passed, 16 failed. Seven failures came from three real gaps in llm4j, now fixed, and a fourth gap was found by reading the responses: an operation the agent does not offer (streaming, an extended card, push notification configuration) answered "method not found" instead of its own error, an error for a request that could not be parsed carried no id, and the `A2A-Version` a client asks for was never checked (and an error for a method the helper does not map carried no id). The mock agent also answered the extended card with the wrong error. Three more failures were caching headers that this scratch server left out, five need a purpose-built agent, and one is the kit's own (below).
- **Server half, after the fixes:** 61 passed, 6 failed. Five of those are artifact tests that expect an agent built to produce particular artifacts (the helloworld agent fails the same five), and one, `CORE-SEND-003`, expects an agent to accept a media type it does not support: llm4j answers with the error the specification names (`ContentTypeNotSupportedError`), the helloworld agent "passes" by accepting anything, and the kit's own check for that error is skipped for both. We believe this is a defect in the kit, and have not confirmed it with its authors.
- **Not checked:** the gRPC and REST bindings, streaming, push notifications, any other SDK, or a signed card.
