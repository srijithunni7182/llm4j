from langchain import OpenAI
from langgraph import Graph


def build(model: OpenAI, send: callable, ask: callable):
    graph = Graph()

    @graph.node
    def generate_reply(question: str):
        reply = model(question)
        return reply

    @graph.node
    def validate_reply(reply: str):
        if "refund" in reply or "credit" in reply or "compensation" in reply:
            return False
        return True

    @graph.node
    def post_reply(reply: str):
        try:
            send(reply)
        except Exception as e:
            print(f"Error sending reply: {e}")

    @graph.node
    def run(question: str):
        reply = generate_reply(question)
        if validate_reply(reply):
            if ask("Can I post this reply to the channel?"):  # Asking for confirmation
                post_reply(reply)
        else:
            print("Reply violates company policy and will not be posted.")

    return run
