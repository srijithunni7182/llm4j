Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangGraph 1.2.18 | planner -> payer with nothing between: the payer paid the amount the planner made up (paid = [99999]); compiling raised 0 warnings. |
| LangChain 1.4.7 | Two create_agent agents, the second handed the first's messages: the payer (a scripted model that repeats the first agent's amount) called the tool and it ran with nothing between the two agents (paid = [99999]); building raised 0 warnings. |
| Google ADK (Python) 2.13.0 | SequentialAgent planner -> payer, the plan passed with output_key and {plan} in the payer's instruction: the payer (a scripted model that repeats the plan's amount) called the tool and it ran with nothing between the two agents (paid = [99999]); building raised 0 warnings. |
| Search of the installed packages | 0 files match a search for an analysis of whether a hand-off between agents is checked. |
