from langchain_core import ChatBot
from langgraph import Workflow


def build(model: ChatBot, send: callable, ask: callable):
    workflow = Workflow()

    @workflow.step
    def draft_reply(question: str):
        # Ensure the question is valid
        if not question:
            return "Please provide a valid question."
        # Get a reply from the model
        reply = model.chat(question)
        return reply

    @workflow.step
    def post_reply(reply: str):
        # Check if the reply violates company rules
        if 'refund' in reply.lower() or 'credit' in reply.lower() or 'compensation' in reply.lower():
            return "The reply cannot promise a refund, credit, or compensation."
        # Send the reply to the channel
        send(reply)
        return "Reply posted to the channel."

    @workflow.step
    def handle_question(question: str):
        reply = draft_reply(question)
        return post_reply(reply)

    return workflow.run(handle_question)