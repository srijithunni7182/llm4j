Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangChain 1.4.7 | SummarizationMiddleware compacted the messages (the run ended with 4 messages after 7 model calls); a field added to the agent's state (progress, through state_schema and an after_model hook that you write) was untouched: progress = 7. |
| LangChain 1.4.7, after a crash | The model call number 4 raised an error. The checkpoint held progress = 3; resuming the same thread with agent.invoke(None, config) finished with progress = 7 (a checkpointer and a thread id are code you write). |
| Google ADK (Python) 2.13.0 | A tool that writes tool_context.state['progress']: after the loop the session state held progress = 6. Session state is kept apart from the message history. (ADK also has a context-compaction setting on an App, which this probe did not run.) |
| LangGraph 1.2.18 | A loop whose compact node removes all but the last two messages: it ended with 2 messages and progress = 6. The progress channel is not touched by the node that edits the messages. |
