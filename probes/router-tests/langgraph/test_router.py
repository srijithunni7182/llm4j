from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage

from router import build


def fake(text: str) -> GenericFakeChatModel:
    return GenericFakeChatModel(messages=iter([AIMessage(content=text)]))


def never() -> GenericFakeChatModel:
    return GenericFakeChatModel(messages=iter([]))  # a call to it raises StopIteration: the branch must not run


def test_order_question_is_answered_by_the_reply_agent():
    g = build(fake("order_status"), fake("It ships tomorrow."), never())
    assert g.invoke({"message": "Where is my parcel?"})["result"] == "It ships tomorrow."


def test_refund_request_goes_to_a_person():
    g = build(fake("refund"), never(), fake("Customer wants a refund."))
    assert g.invoke({"message": "I want my money back."})["result"] == "Customer wants a refund."


def test_anything_else_goes_to_a_person():
    g = build(fake("other"), never(), fake("Customer said hello."))
    assert g.invoke({"message": "Hello"})["result"] == "Customer said hello."
