Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangChain 1.4.7 | A tool that posts and then times out, with metadata={'changes_something': True} on it, under ToolRetryMiddleware: the post happened 2 times. The metadata field is free-form; the retry middleware does not read it, it retries the exceptions and tools you name. Building raised 0 warnings. |
| LangGraph 1.2.18 | A node that posts and then times out, under RetryPolicy(max_attempts=3): the post happened 2 times. A node has no property that says it changes something; the retry policy is per node and does not ask. |
| Google ADK (Python) 2.13.0 | A tool that posts and then times out, under ReflectAndRetryToolPlugin(max_retries=2) with a model that calls it again: the post happened 2 times. The tool carried no statement that it changes something (FunctionTool has no such field; it does have require_confirmation, which was not set in this run). |
| Search of the installed packages | 27 installed files mention side effects or idempotency (this probe did not read each one). The three retry mechanisms exercised above each ran the post twice. First matches: langgraph/langgraph/pregel/_call.py, langgraph/langgraph/pregel/_runner.py, langgraph/langgraph/stream/_mux.py, langgraph/langgraph/stream/run_stream.py. |
