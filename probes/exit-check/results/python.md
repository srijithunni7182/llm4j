Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangGraph 1.2.18 | A graph with a writer node and no check: the rule-breaking reply left as the graph's output = True; building raised 0 warnings. A model's own sending tool call run through the prebuilt ToolNode was executed with nothing in between: sends = 1. |
| LangChain 1.4.7 | create_agent: the model's sending tool call was executed with nothing in between: sends = 1. With a wrap_tool_call middleware that we wrote (a string test for 'refund'), the same call was blocked: sends = 0. The hook exists and works; the rule, and the decision to install it, are code you write. Hooks exported by langchain.agents.middleware: before_agent, before_model, wrap_model_call, after_model, wrap_tool_call, after_agent. |
| Google ADK (Python) 2.13.0 | LlmAgent: the model's sending tool call was executed with nothing in between: sends = 1. With a before_tool_callback we wrote, the same call was blocked: sends = 0. The package also ships a Model Armor integration (a Google Cloud screening service; not run here: it needs a Google Cloud project). |
| Search of the installed packages | 0 files match a search for an analysis that a workflow has an exit with no check before it. Hooks (middleware, callbacks) are things you install; nothing reads the graph and reports where none is installed. |
