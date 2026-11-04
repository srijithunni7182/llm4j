"""P16: a long agent loop that runs past its context window. Is its progress kept where compaction cannot remove it, and does a resume continue from it?"""
import asyncio, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes
from langchain_core.messages import AIMessage

STEPS = 6
rows = []

# ---- LangChain: a field added to the agent's state, with SummarizationMiddleware compacting the messages ----
from typing_extensions import NotRequired
from langchain.agents import create_agent
from langchain.agents.middleware import AgentState, SummarizationMiddleware, after_model
from langchain_core.tools import tool
from langgraph.checkpoint.memory import InMemorySaver

class Progress(AgentState):
    progress: NotRequired[int]

@after_model(state_schema=Progress)
def count_steps(state, runtime):
    return {"progress": state.get("progress", 0) + 1}

@tool
def step(n: int) -> str:
    """Do one step."""
    return f"did {n}"

def calls():
    return [AIMessage(content="", tool_calls=[{"name": "step", "args": {"n": i}, "id": str(i)}]) for i in range(STEPS)] + ["finished"]

def build(model):
    return create_agent(model=model, tools=[step], middleware=[count_steps, SummarizationMiddleware(model=fakes.chat_model(["SUMMARY"] * 20), trigger=("messages", 5), keep=("messages", 2))], checkpointer=InMemorySaver())

cfg = {"configurable": {"thread_id": "t"}, "recursion_limit": 60}
model = fakes.chat_model(calls())
res = build(model).invoke({"messages": [("user", "go")]}, cfg)
rows.append(("LangChain 1.4.7", f"SummarizationMiddleware compacted the messages (the run ended with {len(res['messages'])} messages after {len(model.seen)} model calls); a field added to the agent's state (progress, through state_schema and an after_model hook that you write) was untouched: progress = {res.get('progress')}."))

# a crash part-way, then a resume from the checkpoint
flaky = fakes.chat_model(calls())
original = flaky._generate
count = {"n": 0}
def maybe_fail(messages, *a, **kw):
    count["n"] += 1
    if count["n"] == 4:
        raise RuntimeError("the process died")
    return original(messages, *a, **kw)
object.__setattr__(flaky, "_generate", maybe_fail)
agent = build(flaky)
cfg2 = {"configurable": {"thread_id": "t2"}, "recursion_limit": 60}
try:
    agent.invoke({"messages": [("user", "go")]}, cfg2)
except RuntimeError:
    pass
saved = agent.get_state(cfg2).values.get("progress")
final = agent.invoke(None, cfg2)
rows.append(("LangChain 1.4.7, after a crash", f"The model call number 4 raised an error. The checkpoint held progress = {saved}; resuming the same thread with agent.invoke(None, config) finished with progress = {final.get('progress')} (a checkpointer and a thread id are code you write)."))

# ---- ADK: session state and a tool that writes it --------------------------------------------------------------
from google.adk.agents import LlmAgent
from google.adk.runners import InMemoryRunner
from google.adk.tools import FunctionTool, ToolContext
from google.genai import types

def do_step(n: int, tool_context: ToolContext) -> str:
    """Do one step and remember it."""
    tool_context.state["progress"] = tool_context.state.get("progress", 0) + 1
    return f"did {n}"

fcs = [types.Part(function_call=types.FunctionCall(name="do_step", args={"n": i})) for i in range(STEPS)]
llm = fakes.adk_model(fcs + ["finished"])
agent = LlmAgent(name="a", model=llm, instruction="Work through the steps.", tools=[FunctionTool(do_step)])
runner = InMemoryRunner(agent=agent, app_name="p")
async def go():
    s = await runner.session_service.create_session(app_name="p", user_id="u")
    async for _ in runner.run_async(user_id="u", session_id=s.id, new_message=types.Content(role="user", parts=[types.Part(text="go")])):
        pass
    again = await runner.session_service.get_session(app_name="p", user_id="u", session_id=s.id)
    return again.state.get("progress")
progress = asyncio.run(go())
rows.append(("Google ADK (Python) 2.13.0", f"A tool that writes tool_context.state['progress']: after the loop the session state held progress = {progress}. Session state is kept apart from the message history. (ADK also has a context-compaction setting on an App, which this probe did not run.)"))

# ---- LangGraph: a loop that compacts its messages and keeps a progress channel -------------------------------
from typing import Annotated, TypedDict
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langchain_core.messages import HumanMessage, RemoveMessage

class Loop(TypedDict):
    messages: Annotated[list, add_messages]
    progress: int

def work(st):
    return {"messages": [HumanMessage(f"step {st['progress'] + 1} done")], "progress": st["progress"] + 1}

def compact(st):
    old = st["messages"][:-2]
    return {"messages": [RemoveMessage(id=m.id) for m in old]}

def more(st):
    return "work" if st["progress"] < STEPS else END

g = StateGraph(Loop)
g.add_node("work", work); g.add_node("compact", compact)
g.add_edge(START, "work"); g.add_edge("work", "compact"); g.add_conditional_edges("compact", more)
out = g.compile().invoke({"messages": [], "progress": 0})
rows.append(("LangGraph 1.2.18", f"A loop whose compact node removes all but the last two messages: it ended with {len(out['messages'])} messages and progress = {out['progress']}. The progress channel is not touched by the node that edits the messages."))

fakes.report(rows)
