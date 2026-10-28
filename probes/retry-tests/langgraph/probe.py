"""Probe P4, LangGraph side: a model that fails twice, with ModelRetryMiddleware(max_retries=2, initial_delay=2.0, backoff_factor=2.0).
How long does the test take? (A person who wants this tested quickly has to change the delays under test, or patch time.sleep.)"""
import sys
import time

from langchain.agents import create_agent
from langchain.agents.middleware import ModelRetryMiddleware
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult


class FlakyModel(BaseChatModel):
    failures: int = 2
    calls: int = 0

    @property
    def _llm_type(self) -> str:
        return "flaky"

    def _generate(self, messages, stop=None, run_manager=None, **kwargs) -> ChatResult:
        self.calls += 1
        if self.calls <= self.failures:
            raise TimeoutError("the model timed out")
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content="hello"))])

    def bind_tools(self, tools, **kwargs):
        return self


def main(delay: float) -> None:
    model = FlakyModel()
    agent = create_agent(
        model,
        tools=[],
        middleware=[ModelRetryMiddleware(max_retries=2, initial_delay=delay, backoff_factor=2.0, jitter=False)],
    )
    start = time.perf_counter()
    result = agent.invoke({"messages": [("user", "hi")]})
    elapsed = time.perf_counter() - start
    print(f"initial_delay={delay}s: calls={model.calls} answer={result['messages'][-1].content!r} took {elapsed:.2f}s")


if __name__ == "__main__":
    main(float(sys.argv[1]) if len(sys.argv) > 1 else 2.0)
