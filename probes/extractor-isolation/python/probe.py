"""P9: a step that pulls fields out of text. Can it be given only its inputs, and does anything notice when it is not?"""
import os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes

EARLIER = "EARLIER-SECRET-NOTE (said by an earlier step)"
TEXT = "Invoice from Acme, total 240"
rows = []

# ---- LangGraph: a node is a function, so "only its inputs" is code you write ------------------
from typing import TypedDict, Annotated
from langgraph.graph import StateGraph, START, END
from langgraph.graph.message import add_messages
from langchain_core.messages import HumanMessage

class State(TypedDict):
    messages: Annotated[list, add_messages]
    text: str

def build_graph(extractor_sees_history):
    chat = fakes.chat_model([EARLIER])
    extract = fakes.chat_model(['{"vendor":"Acme","total":240}'])
    def chat_node(s): return {"messages": [chat.invoke(s["messages"])]}
    def extract_node(s):
        request = s["messages"] if extractor_sees_history else [HumanMessage(s["text"])]
        return {"messages": [extract.invoke(request)]}
    g = StateGraph(State)
    g.add_node("chat", chat_node); g.add_node("extract", extract_node)
    g.add_edge(START, "chat"); g.add_edge("chat", "extract"); g.add_edge("extract", END)
    return g, extract

with fakes.Quiet() as q:
    g, extract_isolated = build_graph(False); g.compile().invoke({"messages": [HumanMessage("hello")], "text": TEXT})
    g2, extract_history = build_graph(True); g2.compile().invoke({"messages": [HumanMessage("hello")], "text": TEXT})
iso = any(EARLIER in s for call in extract_isolated.seen for s in call)
hist = any(EARLIER in s for call in extract_history.seen for s in call)
rows.append(("LangGraph 1.2.18", f"A node that passes only the text: the extractor's request had the earlier step's output = {iso}. The same node written to pass the graph's messages: {hist}. Building and compiling both raised {len(q.messages)} warnings, so nothing noticed the difference before the run."))

# ---- LangChain: create_agent keeps the message history in its state ---------------------------
from langchain.agents import create_agent
chat = fakes.chat_model([EARLIER]); extract = fakes.chat_model(['{"vendor":"Acme"}'])
with fakes.Quiet() as q2:
    a1 = create_agent(model=chat, tools=[]); a2 = create_agent(model=extract, tools=[])
    r1 = a1.invoke({"messages": [("user", "hello")]})
    a2.invoke({"messages": r1["messages"] + [("user", TEXT)]})
carried = any(EARLIER in s for call in extract.seen for s in call)
rows.append(("LangChain 1.4.7", f"An agent made with create_agent receives the messages it is given; handing it the previous agent's messages put the earlier output in its request = {carried}. Handing it only the text is a call you write. Building raised {len(q2.messages)} warnings."))

# ---- ADK: include_contents, and AgentTool -----------------------------------------------------
from google.adk.agents import LlmAgent, SequentialAgent
from google.adk.tools.agent_tool import AgentTool
from google.genai import types

def flat(model):
    return " ".join(t for call in model.seen for _, ts in call["contents"] for t in ts)

def adk_pipeline(include):
    chat = fakes.adk_model([EARLIER]); extract = fakes.adk_model(['{"vendor":"Acme"}'])
    with fakes.Quiet() as q:
        seq = SequentialAgent(name="seq", sub_agents=[
            LlmAgent(name="chat", model=chat, instruction="Chat."),
            LlmAgent(name="extract", model=extract, instruction="Extract fields.", include_contents=include)])
    fakes.adk_run(seq, TEXT)
    return flat(extract), q

d_text, q3 = adk_pipeline("default"); n_text, q4 = adk_pipeline("none")
rows.append(("Google ADK (Python) 2.13.0, SequentialAgent", f"include_contents='default': the extractor's request had the earlier step's output = {EARLIER in d_text}, and the user's text = {TEXT in d_text}. include_contents='none': earlier output = {EARLIER in n_text}, user's text = {TEXT in n_text}. So in this version the setting removes the user's message but the previous agent's output still arrived. Building raised {len(q3.messages) + len(q4.messages)} warnings."))

# the extractor as a tool of a parent agent: its request is the tool call's arguments
extract = fakes.adk_model(['{"vendor":"Acme"}'])
call = types.Part(function_call=types.FunctionCall(name="extract", args={"request": TEXT}))
parent = LlmAgent(name="parent", model=fakes.adk_model([call, "done"]), instruction="Delegate.",
                  tools=[AgentTool(agent=LlmAgent(name="extract", model=extract, instruction="Extract fields."))])
fakes.adk_run(parent, "hello " + EARLIER)
t = flat(extract)
rows.append(("Google ADK (Python) 2.13.0, AgentTool", f"The extractor wrapped as an AgentTool and called by a parent that had seen the earlier text: its request had the earlier text = {EARLIER in t}, and the text it was given = {TEXT in t}. It runs on only the arguments of the call."))

# ---- is there any analysis that would notice? ---------------------------------------------------
packages = ["langgraph", "langchain", "langchain-core", "google-adk"]
count, files = fakes.search(packages, r"\b(least[- ]privilege|isolat\w* (the )?(context|history)|stateless (node|step))\b")
rows.append(("Search of the installed packages", f"{count} files mention least-privilege or stateless steps (searched langgraph, langchain, langchain-core, google-adk source); none of the matches is an analysis of a graph before it runs."))

fakes.report(rows)
