Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangGraph 1.2.18 | A node that passes only the text: the extractor's request had the earlier step's output = False. The same node written to pass the graph's messages: True. Building and compiling both raised 0 warnings, so nothing noticed the difference before the run. |
| LangChain 1.4.7 | An agent made with create_agent receives the messages it is given; handing it the previous agent's messages put the earlier output in its request = True. Handing it only the text is a call you write. Building raised 0 warnings. |
| Google ADK (Python) 2.13.0, SequentialAgent | include_contents='default': the extractor's request had the earlier step's output = True, and the user's text = True. include_contents='none': earlier output = True, user's text = False. So in this version the setting removes the user's message but the previous agent's output still arrived. Building raised 0 warnings. |
| Google ADK (Python) 2.13.0, AgentTool | The extractor wrapped as an AgentTool and called by a parent that had seen the earlier text: its request had the earlier text = False, and the text it was given = True. It runs on only the arguments of the call. |
| Search of the installed packages | 0 files mention least-privilege or stateless steps (searched langgraph, langchain, langchain-core, google-adk source); none of the matches is an analysis of a graph before it runs. |
