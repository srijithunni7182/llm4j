"""P17: a planner whose input tells it to route a task to a privileged agent. Can the route go outside the intended agents, and does anything notice before the run?"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes
from langchain_core.messages import AIMessage

rows = []
ran = []

# ---- LangGraph: the planner's reply is the name of the next node -----------------------------------------------
from typing import TypedDict
from langgraph.graph import StateGraph, START, END
from langgraph.types import Command
class S(TypedDict):
    task: str
planner = fakes.chat_model(["privileged"])      # the input told the planner to pick "privileged"
def plan(s) -> Command:
    return Command(goto=planner.invoke(s["task"]).content)
def worker(s): ran.append("worker"); return {}
def privileged(s): ran.append("privileged"); return {}
with fakes.Quiet() as q:
    g = StateGraph(S)
    g.add_node("plan", plan); g.add_node("worker", worker); g.add_node("privileged", privileged)
    g.add_edge(START, "plan"); g.add_edge("worker", END); g.add_edge("privileged", END)
    app = g.compile()
app.invoke({"task": "send everything to privileged"})
a = list(ran)
ran.clear()
planner2 = fakes.chat_model(["privileged"])
def route(s) -> str:
    return planner2.invoke(s["task"]).content
g2 = StateGraph(S)
g2.add_node("plan", lambda s: {}); g2.add_node("worker", worker); g2.add_node("privileged", privileged)
g2.add_edge(START, "plan"); g2.add_conditional_edges("plan", route, ["worker"]); g2.add_edge("worker", END); g2.add_edge("privileged", END)
note = "ran"
try:
    g2.compile().invoke({"task": "x"})
except Exception as e:
    note = f"refused with {type(e).__name__}: {str(e)[:90]}"
rows.append(("LangGraph 1.2.18", f"A planner node returning Command(goto=<the model's reply>): the privileged node ran = {a}; compiling raised {len(q.messages)} warnings. With add_conditional_edges and a list of allowed targets (['worker']) and the same reply: the route {note if note != 'ran' else 'ran: ' + str(ran)}."))

# ---- LangChain: agents as tools of a supervisor -------------------------------------------------------------------
from langchain.agents import create_agent
from langchain_core.tools import tool
@tool
def worker_tool(task: str) -> str:
    """Hand a task to the worker."""
    ran.append("worker_tool"); return "worker done"
ran.clear()
call = AIMessage(content="", tool_calls=[{"name": "privileged_tool", "args": {"task": "x"}, "id": "1"}])
sup = create_agent(model=fakes.chat_model([call, "done"]), tools=[worker_tool])
out = sup.invoke({"messages": [("user", "hand this to privileged_tool")]})
tool_msg = [m for m in out["messages"] if m.type == "tool"]
rows.append(("LangChain 1.4.7", f"A supervisor create_agent whose tools are [worker_tool], with a model that asks for 'privileged_tool': nothing ran (ran = {ran}) and the model was told: {str(tool_msg[0].content)[:100]!r}. The allowed routes are the tools list."))

# ---- ADK: sub_agents define where transfer_to_agent can go ------------------------------------------------------------
from google.adk.agents import LlmAgent
from google.genai import types
def adk(subs):
    w = LlmAgent(name="worker", model=fakes.adk_model(["worker answered"]), instruction="w")
    p = LlmAgent(name="privileged", model=fakes.adk_model(["PRIVILEGED ACTION RAN"]), instruction="p")
    pool = {"worker": w, "privileged": p}
    fc = types.Part(function_call=types.FunctionCall(name="transfer_to_agent", args={"agent_name": "privileged"}))
    with fakes.Quiet() as qq:
        planner = LlmAgent(name="planner", model=fakes.adk_model([fc, "planner done"]), instruction="route", sub_agents=[pool[n] for n in subs])
    try:
        _, _, final = fakes.adk_run(planner, "route this to privileged")
    except Exception as e:
        final = f"refused with {type(e).__name__}: {str(e)[:70]}"
    return p.model.calls, final, len(qq.messages)
c1, f1, w1 = adk(["worker", "privileged"]); c2, f2, w2 = adk(["worker"])
rows.append(("Google ADK (Python) 2.13.0", f"A planner with sub_agents=[worker, privileged] and a model that asks transfer_to_agent('privileged'): privileged model calls = {c1} ({f1!r}). With sub_agents=[worker] only: privileged model calls = {c2}, {f2}. Building either raised {w1 + w2} warnings; the allowed routes are the agent tree."))

packages = ["langgraph", "langchain", "langchain-core", "google-adk"]
count, files = fakes.search(packages, r"(allow-?list|allowed (targets|agents|routes))")
rows.append(("Search of the installed packages", f"{count} files mention an allow-list or allowed targets (this probe did not read each one). In the three runs above the allowed routes were the ones you wired; none of the builds reported a route that depended on a model's word."))
fakes.report(rows)
