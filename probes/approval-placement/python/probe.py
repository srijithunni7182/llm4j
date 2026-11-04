"""P12: a sending tool that needs a person's yes. Where does the run pause, and does anything count the questions one run can ask before it runs?"""
import asyncio, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes
from langchain_core.messages import AIMessage

rows = []
sends = []

def send_email(to: str, body: str) -> str:
    """Send an email."""
    sends.append(body)
    return "sent"

# ---- LangGraph: interrupt() inside a node ---------------------------------------------------------
from typing import TypedDict
from langgraph.graph import StateGraph, START, END
from langgraph.types import interrupt, Command
from langgraph.checkpoint.memory import InMemorySaver

class S(TypedDict):
    draft: str
drafter = fakes.chat_model(["draft one", "draft two", "draft three"])
def node(s):
    text = drafter.invoke("write").content        # a model call BEFORE the question, in the same node
    answer = interrupt(f"Send this? {text}")
    if answer == "yes":
        send_email("a@example.com", text)
    return {"draft": text}
g = StateGraph(S); g.add_node("send", node); g.add_edge(START, "send"); g.add_edge("send", END)
app = g.compile(checkpointer=InMemorySaver())
cfg = {"configurable": {"thread_id": "t"}}
r1 = app.invoke({"draft": ""}, cfg)
paused = "__interrupt__" in r1
before = (len(sends), len(drafter.seen))
app.invoke(Command(resume="yes"), cfg)
rows.append(("LangGraph 1.2.18", f"interrupt() inside the node that drafts and sends: the run paused = {paused} with sends = {before[0]} and model calls = {before[1]}. After the answer 'yes' the node started again from its first line: model calls = {len(drafter.seen)}, sends = {len(sends)}, and the text sent was {sends[0]!r} while the person was shown 'draft one'. The pause is wherever the code calls interrupt()."))

# ---- LangChain: HumanInTheLoopMiddleware ----------------------------------------------------------
from langchain.agents import create_agent
from langchain.agents.middleware import HumanInTheLoopMiddleware
sends.clear()
call = AIMessage(content="", tool_calls=[{"name": "send_email", "args": {"to": "a@example.com", "body": "hi"}, "id": "1"}])
m = fakes.chat_model([call, "done"])
with fakes.Quiet() as q:
    a = create_agent(model=m, tools=[send_email], middleware=[HumanInTheLoopMiddleware(interrupt_on={"send_email": True})], checkpointer=InMemorySaver())
r = a.invoke({"messages": [("user", "go")]}, cfg)
p = ("__interrupt__" in r, len(sends), len(m.seen))
a.invoke(Command(resume={"decisions": [{"type": "approve"}]}), cfg)
rows.append(("LangChain 1.4.7", f"HumanInTheLoopMiddleware(interrupt_on={{'send_email': True}}): the run paused before the tool call, inside the agent's loop (paused = {p[0]}, sends = {p[1]}, model calls = {p[2]}); after an approving decision the tool ran (sends = {len(sends)}) and the model was called once more to write its final answer (calls = {len(m.seen)}); the tool call was not asked of the model again. Building raised {len(q.messages)} warnings."))

# ---- ADK: require_confirmation ----------------------------------------------------------------------
from google.adk.agents import LlmAgent
from google.adk.tools import FunctionTool
from google.adk.runners import InMemoryRunner
from google.genai import types
sends.clear()
fc = types.Part(function_call=types.FunctionCall(name="send_email", args={"to": "a@example.com", "body": "hi"}))
llm = fakes.adk_model([fc, "done"])
agent = LlmAgent(name="a", model=llm, instruction="x", tools=[FunctionTool(send_email, require_confirmation=True)])
runner = InMemoryRunner(agent=agent, app_name="p")
asked = []
async def go():
    s = await runner.session_service.create_session(app_name="p", user_id="u")
    async for ev in runner.run_async(user_id="u", session_id=s.id, new_message=types.Content(role="user", parts=[types.Part(text="go")])):
        for part in (ev.content.parts if ev.content else []):
            if part.function_call and part.function_call.name == "adk_request_confirmation":
                asked.append(part.function_call.name)
asyncio.run(go())
rows.append(("Google ADK (Python) 2.13.0", f"FunctionTool(send_email, require_confirmation=True): the run emitted a confirmation request ({len(asked)}) and the tool had not run (sends = {len(sends)}, model calls = {llm.calls}). The question is attached to the tool, so it is asked at the tool call."))

# ---- does anything count the questions one run can ask, before it runs? ---------------------------------
packages = ["langgraph", "langchain", "langchain-core", "google-adk"]
count, files = fakes.search(packages, r"(number of (approvals|questions|interrupts)|count(s|ing)? (the )?(approvals|interrupts|questions)|how many (approvals|interrupts|questions))")
rows.append(("Search of the installed packages", f"{count} files match a search for something that counts the questions or interrupts a run can raise before it runs. In each framework the interrupt points are in your code or middleware; none of the three builds raised a warning about how many there are."))
fakes.report(rows)
