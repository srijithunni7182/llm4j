"""P14: a tool that posts. Is "it changes something" stated once on the tool and honoured by approvals, retries and tests?"""
import asyncio, os, sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "_common"))
import fakes
from langchain_core.messages import AIMessage

rows = []
posts = []

def make_post():
    state = {"n": 0}
    def post(text: str) -> str:
        """Post a message to the team channel."""
        posts.append(text)                 # the effect happens...
        state["n"] += 1
        if state["n"] == 1:
            raise TimeoutError("the reply was lost")   # ...and then the caller never hears of it
        return "posted"
    return post

# ---- LangChain: ToolRetryMiddleware -------------------------------------------------------------------
from langchain.agents import create_agent
from langchain.agents.middleware import ToolRetryMiddleware
from langchain_core.tools import tool
post = make_post()
t = tool(post, description="Post a message to the team channel.")
t.metadata = {"changes_something": True}     # a free-form field: nothing reads it
call = AIMessage(content="", tool_calls=[{"name": "post", "args": {"text": "hello"}, "id": "1"}])
posts.clear()
with fakes.Quiet() as q:
    a = create_agent(model=fakes.chat_model([call, "done"]), tools=[t], middleware=[ToolRetryMiddleware(max_retries=2, initial_delay=0.0, retry_on=(TimeoutError,))])
a.invoke({"messages": [("user", "go")]})
rows.append(("LangChain 1.4.7", f"A tool that posts and then times out, with metadata={{'changes_something': True}} on it, under ToolRetryMiddleware: the post happened {len(posts)} times. The metadata field is free-form; the retry middleware does not read it, it retries the exceptions and tools you name. Building raised {len(q.messages)} warnings."))

# ---- LangGraph: RetryPolicy on a node ---------------------------------------------------------------------------
from typing import TypedDict
from langgraph.graph import StateGraph, START, END
from langgraph.types import RetryPolicy
post2 = make_post(); posts.clear()
class S(TypedDict):
    x: str
g = StateGraph(S)
g.add_node("post", lambda s: {"x": post2("hello")}, retry_policy=RetryPolicy(max_attempts=3, initial_interval=0.0, retry_on=TimeoutError))
g.add_edge(START, "post"); g.add_edge("post", END)
g.compile().invoke({"x": ""})
rows.append(("LangGraph 1.2.18", f"A node that posts and then times out, under RetryPolicy(max_attempts=3): the post happened {len(posts)} times. A node has no property that says it changes something; the retry policy is per node and does not ask."))

# ---- ADK -------------------------------------------------------------------------------------------------------------
from google.adk.agents import LlmAgent
from google.adk.plugins.reflect_retry_tool_plugin import ReflectAndRetryToolPlugin
from google.adk.runners import InMemoryRunner
from google.adk.tools import FunctionTool
from google.genai import types
post3 = make_post(); posts.clear()
fc = types.Part(function_call=types.FunctionCall(name="post", args={"text": "hello"}))
agent = LlmAgent(name="a", model=fakes.adk_model([fc, fc, "done"]), instruction="x", tools=[FunctionTool(post3)])
runner = InMemoryRunner(agent=agent, app_name="p", plugins=[ReflectAndRetryToolPlugin(max_retries=2)])
async def go():
    s = await runner.session_service.create_session(app_name="p", user_id="u")
    async for _ in runner.run_async(user_id="u", session_id=s.id, new_message=types.Content(role="user", parts=[types.Part(text="go")])):
        pass
asyncio.run(go())
rows.append(("Google ADK (Python) 2.13.0", f"A tool that posts and then times out, under ReflectAndRetryToolPlugin(max_retries=2) with a model that calls it again: the post happened {len(posts)} times. The tool carried no statement that it changes something (FunctionTool has no such field; it does have require_confirmation, which was not set in this run)."))

packages = ["langgraph", "langchain", "langchain-core", "google-adk"]
count, files = fakes.search(packages, r"(side[_ -]?effect|idempoten|has[_ ]effects|changes[_ ]state)")
rows.append(("Search of the installed packages", f"{count} installed files mention side effects or idempotency (this probe did not read each one). The three retry mechanisms exercised above each ran the post twice. First matches: {', '.join(files[:4])}."))
fakes.report(rows)
