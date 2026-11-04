"""P13: an evaluator or guardrail that enforces a rule. Does anything stop it being used with no test of it?"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes

rows = []
packages = ["langgraph", "langchain", "langchain-core", "google-adk"]

# ---- LangChain: a guardrail written as middleware ---------------------------------------------------
from langchain.agents import create_agent
from langchain.agents.middleware import after_model
from langchain_core.messages import AIMessage

@after_model
def no_refund_promises(state, runtime):
    last = state["messages"][-1]
    if isinstance(last, AIMessage) and "refund" in str(last.content).lower():
        return {"messages": [AIMessage(content="I cannot promise that.")]}
    return None

with fakes.Quiet() as q:
    agent = create_agent(model=fakes.chat_model(["We will refund you."]), tools=[], middleware=[no_refund_promises])
out = agent.invoke({"messages": [("user", "hi")]})["messages"][-1].content
rows.append(("LangChain 1.4.7", f"A guardrail written as after_model middleware, with no test of it anywhere in the project: built and run with {len(q.messages)} warnings (the run answered {out!r}). Nothing asked whether the guardrail had been tested."))

# ---- LangGraph ----------------------------------------------------------------------------------------
from typing import TypedDict
from langgraph.graph import StateGraph, START, END
class S(TypedDict):
    reply: str
with fakes.Quiet() as q2:
    g = StateGraph(S)
    g.add_node("write", lambda s: {"reply": "We will refund you."})
    g.add_node("guard", lambda s: {"reply": "I cannot promise that." if "refund" in s["reply"] else s["reply"]})
    g.add_edge(START, "write"); g.add_edge("write", "guard"); g.add_edge("guard", END)
    res = g.compile().invoke({"reply": ""})
rows.append(("LangGraph 1.2.18", f"A guard node, with no test of it: compiled and run with {len(q2.messages)} warnings (the run answered {res['reply']!r})."))

# ---- ADK -------------------------------------------------------------------------------------------------
from google.adk.agents import LlmAgent
def guard(callback_context, llm_response):
    return None
with fakes.Quiet() as q3:
    a = LlmAgent(name="a", model=fakes.adk_model(["We will refund you."]), instruction="x", after_model_callback=guard)
_, _, final = fakes.adk_run(a, "hi")
rows.append(("Google ADK (Python) 2.13.0", f"A guardrail written as an after_model_callback, with no test of it: built and run with {len(q3.messages)} warnings. ADK ships an evaluation framework (google.adk.evaluation) that tests agents when you run it; nothing connects it to the build of an agent."))

count, files = fakes.search(packages, r"(untested|no tests? (for|of)|requires? (a )?test|must (have|be) tested)")
rows.append(("Search of the installed packages", f"{count} files match a search for a check that a guard or evaluator has a test before it is used; none of the matches is one."))
fakes.report(rows)
