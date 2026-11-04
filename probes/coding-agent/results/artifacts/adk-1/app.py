# Mock implementation for testing purposes
class MockModel:
    def generate(self, question):
        # A simple mock response based on the question
        return "Here is a response to your question: '" + question + "'"


def build(model, send, ask):
    class Workflow:
        def run(self, question: str):
            # Draft a reply using the model
            reply = model.generate(question)
            
            # Check if the reply contains any promises of refund or compensation
            if 'refund' in reply.lower() or 'credit' in reply.lower() or 'compensation' in reply.lower():
                reply = "I cannot promise any refunds or compensations."  # Modify the reply accordingly
            
            # Send the reply to the channel
            send(reply)

    return Workflow()