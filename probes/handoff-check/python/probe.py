"""P15: one agent's invented amount handed to an agent that pays. Does anything notice, before the run, that nothing checks the hand-off?"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes
from langchain_core.messages import AIMessage

INVENTED = "The refund owed is 99999 for order 7."     # the planner makes the amount up
rows = []
paid = []

def pay(amount: int, order: str) -> str:
    """Pay a refund."""
    paid.append(amount)
    return "paid"

# ---- LangGraph -------------------------------------------------------------------------------------
from typing import TypedDict
from langgraph.graph import StateGraph, START, END
class S(TypedDict):
    plan: str
    done: str
planner = fakes.chat_model([INVENTED])
def payer(s):
    import re
    pay(int(re.search(r"\d{3,}", s["plan"]).group()), "7")
    return {"done": "ok"}
with fakes.Quiet() as q:
    g = StateGraph(S)
    g.add_node("planner", lambda s: {"plan": planner.invoke("complaint").content})
    g.add_node("payer", payer)
    g.add_edge(START, "planner"); g.add_edge("planner", "payer"); g.add_edge("payer", END)
    g.compile().invoke({"plan": "", "done": ""})
rows.append(("LangGraph 1.2.18", f"planner -> payer with nothing between: the payer paid the amount the planner made up (paid = {paid}); compiling raised {len(q.messages)} warnings."))

# ---- LangChain -------------------------------------------------------------------------------------
from langchain.agents import create_agent
paid.clear()
call = AIMessage(content="", tool_calls=[{"name": "pay", "args": {"amount": 99999, "order": "7"}, "id": "1"}])
with fakes.Quiet() as q2:
    planner_agent = create_agent(model=fakes.chat_model([INVENTED]), tools=[])
    payer_agent = create_agent(model=fakes.chat_model([call, "done"]), tools=[pay])
r = planner_agent.invoke({"messages": [("user", "complaint")]})
payer_agent.invoke({"messages": r["messages"]})
rows.append(("LangChain 1.4.7", f"Two create_agent agents, the second handed the first's messages: the payer (a scripted model that repeats the first agent's amount) called the tool and it ran with nothing between the two agents (paid = {paid}); building raised {len(q2.messages)} warnings."))

# ---- ADK -------------------------------------------------------------------------------------------
from google.adk.agents import LlmAgent, SequentialAgent
from google.genai import types
paid.clear()
fc = types.Part(function_call=types.FunctionCall(name="pay", args={"amount": 99999, "order": "7"}))
with fakes.Quiet() as q3:
    seq = SequentialAgent(name="seq", sub_agents=[
        LlmAgent(name="planner", model=fakes.adk_model([INVENTED]), instruction="Plan.", output_key="plan"),
        LlmAgent(name="payer", model=fakes.adk_model([fc, "done"]), instruction="Pay what the plan says: {plan}", tools=[pay])])
fakes.adk_run(seq, "complaint")
rows.append(("Google ADK (Python) 2.13.0", f"SequentialAgent planner -> payer, the plan passed with output_key and {{plan}} in the payer's instruction: the payer (a scripted model that repeats the plan's amount) called the tool and it ran with nothing between the two agents (paid = {paid}); building raised {len(q3.messages)} warnings."))

packages = ["langgraph", "langchain", "langchain-core", "google-adk"]
count, files = fakes.search(packages, r"(unvalidated|unchecked) (hand-?off|output|text)|hand-?off .{0,30}(check|validat)")
rows.append(("Search of the installed packages", f"{count} files match a search for an analysis of whether a hand-off between agents is checked."))
fakes.report(rows)
