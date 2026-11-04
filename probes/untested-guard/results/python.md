Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangChain 1.4.7 | A guardrail written as after_model middleware, with no test of it anywhere in the project: built and run with 0 warnings (the run answered 'I cannot promise that.'). Nothing asked whether the guardrail had been tested. |
| LangGraph 1.2.18 | A guard node, with no test of it: compiled and run with 0 warnings (the run answered 'I cannot promise that.'). |
| Google ADK (Python) 2.13.0 | A guardrail written as an after_model_callback, with no test of it: built and run with 0 warnings. ADK ships an evaluation framework (google.adk.evaluation) that tests agents when you run it; nothing connects it to the build of an agent. |
| Search of the installed packages | 0 files match a search for a check that a guard or evaluator has a test before it is used; none of the matches is one. |
