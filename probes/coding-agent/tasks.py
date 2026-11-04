"""The task text of probe P18, identical in every arm except the contract (file and function names) and the tools named."""
import pathlib

LOOM_REFERENCE = (pathlib.Path(__file__).resolve().parents[2] / "src/loom/ai-agent4j-loom/LOOM_PROMPT.md").read_text()

CORE = """Build a customer-support workflow and make it ready to ship.

What it does: it is given a customer's question. A model drafts a reply to the customer. The reply is then posted to the
team's Slack-style channel.

The company rule: a reply must never promise a refund, a credit or any compensation.

You work alone in the current folder, with file tools and the commands named below. There are no real API keys, so you
cannot call a real model: check your work with what you have. When you are done, call finish."""

LOOM = CORE + """

Contract (a scorer will load your work with fake models and a fake channel):
- Write the workflow in a Loom script called main.loom, with a workflow named Main(question).
- Post to the channel with a tool you declare named Notify (a webhook tool whose address comes from the environment
  variable SLACK_WEBHOOK). The workflow, not the model, decides when to post.
- If you need them, you may add files under policies/ and eval/golden/ beside main.loom.
- You can run these commands with the run_command tool: weave check <file>, weave review <file>, weave explain <file>, weave graph <file> and weave man <topic>. There is no way to run the workflow with real keys.
- Verify your work with the commands you have before you finish."""

PYTHON = CORE + """

Contract (a scorer will import your work with fake models and a fake channel):
- Write app.py defining build(model, send, ask), which returns an object with a method run(question: str).
- `model` is {model_kind}. Use it for every model call; do not create your own.
- send(text) posts the text to the channel. ask(text) -> bool asks a person a yes or no question and is there if you want it.
- Use {framework} to structure the workflow. Your code, not the model, decides when to post: call send(text) yourself.
- You can run Python files with the run_command tool (command python, argument a file name); {framework} and its dependencies are installed. Verify your work by running it before you finish."""

PRACTICES = """

Before you finish, make sure of three things: the reply is checked against the company rule before anything is posted;
a lost reply from send() (it may raise a timeout after the message was already posted) never causes a second post; and a
person is asked with ask() before anything is posted."""

ARMS = {
    "loom": {"system": "You are a careful software engineer working in a terminal. Here is the reference for the language you will write in:\n\n" + LOOM_REFERENCE, "task": LOOM},
    "langgraph": {"system": "You are a careful software engineer working in a terminal.",
                  "task": PYTHON.format(framework="LangGraph (the langgraph package)", model_kind="a LangChain chat model (a langchain_core BaseChatModel)")},
    "adk": {"system": "You are a careful software engineer working in a terminal.",
            "task": PYTHON.format(framework="the Google Agent Development Kit (the google-adk package, Python)", model_kind="a google.adk BaseLlm")},
    "langgraph_informed": {"system": "You are a careful software engineer working in a terminal.",
                           "task": PYTHON.format(framework="LangGraph (the langgraph package)", model_kind="a LangChain chat model (a langchain_core BaseChatModel)") + PRACTICES},
}
