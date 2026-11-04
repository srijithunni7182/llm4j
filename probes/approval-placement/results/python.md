Versions: langgraph 1.2.18, langchain 1.4.7, langchain-core 1.7.4, google-adk 2.13.0, Python 3.13.18

| Framework | Finding |
|---|---|
| LangGraph 1.2.18 | interrupt() inside the node that drafts and sends: the run paused = True with sends = 0 and model calls = 1. After the answer 'yes' the node started again from its first line: model calls = 2, sends = 1, and the text sent was 'draft two' while the person was shown 'draft one'. The pause is wherever the code calls interrupt(). |
| LangChain 1.4.7 | HumanInTheLoopMiddleware(interrupt_on={'send_email': True}): the run paused before the tool call, inside the agent's loop (paused = True, sends = 0, model calls = 1); after an approving decision the tool ran (sends = 1) and the model was called once more to write its final answer (calls = 2); the tool call was not asked of the model again. Building raised 0 warnings. |
| Google ADK (Python) 2.13.0 | FunctionTool(send_email, require_confirmation=True): the run emitted a confirmation request (1) and the tool had not run (sends = 0, model calls = 1). The question is attached to the tool, so it is asked at the tool call. |
| Search of the installed packages | 0 files match a search for something that counts the questions or interrupts a run can raise before it runs. In each framework the interrupt points are in your code or middleware; none of the three builds raised a warning about how many there are. |
