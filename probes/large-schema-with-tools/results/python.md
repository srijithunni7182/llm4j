Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangChain 1.4.7 | create_agent with one tool and response_format=Order (nested objects, a list of objects, 8 top-level fields) built with 0 warnings and was accepted. |
| LangGraph 1.2.18 | A node that binds a tool to a model and is expected to answer in Order: built and compiled with 0 warnings. The schema is not part of the graph; the node holds it in its own code. |
| Google ADK (Python) 2.13.0 | LlmAgent(tools=[lookup], output_schema=Order) was accepted with 0 warnings. |
| Search of the installed packages | 0 files match a search for a warning about large or nested schemas, or about reasoning and a schema together. |
