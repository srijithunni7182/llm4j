Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangGraph 1.2.18 | A planner node returning Command(goto=<the model's reply>): the privileged node ran = ['privileged']; compiling raised 0 warnings. With add_conditional_edges and a list of allowed targets (['worker']) and the same reply: the route refused with KeyError: 'privileged'. |
| LangChain 1.4.7 | A supervisor create_agent whose tools are [worker_tool], with a model that asks for 'privileged_tool': nothing ran (ran = []) and the model was told: 'Error: privileged_tool is not a valid tool, try one of [worker_tool].'. The allowed routes are the tools list. |
| Google ADK (Python) 2.13.0 | A planner with sub_agents=[worker, privileged] and a model that asks transfer_to_agent('privileged'): privileged model calls = 1 ('PRIVILEGED ACTION RAN'). With sub_agents=[worker] only: privileged model calls = 0, refused with ValueError: Transfer target agent 'privileged' not found.. Building either raised 0 warnings; the allowed routes are the agent tree. |
| Search of the installed packages | 17 files mention an allow-list or allowed targets (this probe did not read each one). In the three runs above the allowed routes were the ones you wired; none of the builds reported a route that depended on a model's word. |
