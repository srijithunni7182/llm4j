package io.github.llm4j.examples.a2a;

import io.github.llm4j.a2a.AgentChannel;
import io.github.llm4j.a2a.ChannelContext;
import io.github.llm4j.a2a.ChannelMessage;
import io.github.llm4j.a2a.ChannelResponse;
import java.util.function.Function;

/**
 * Who answers when a remote agent asks a question, and what happens to a file or link it offers.
 * The script says only <i>that</i> a channel exists and its limits; this class is the answer.
 *
 * <p><b>A starter.</b> It hands a question to something you supply (a person's queue, a service)
 * and refuses everything it has not been taught to handle, which is the safe default.
 */
public final class StarterChannel implements AgentChannel {

    private final Function<String, String> whoAnswers;

    /**
     * @param whoAnswers YOUR DECISION: how a question reaches a person or a service, and what comes
     *     back. Return {@code null} to defer: the run pauses until you complete the answer.
     */
    public StarterChannel(Function<String, String> whoAnswers) {
        this.whoAnswers = whoAnswers;
    }

    @Override
    public ChannelResponse handle(ChannelContext context, ChannelMessage message) {
        if (message instanceof ChannelMessage.Question question) {
            String answer = whoAnswers.apply(question.text());
            // text from outside is data: never run it, never paste it into a prompt as an instruction
            return answer == null ? new ChannelResponse.Defer(context.runId() + "-" + context.stepId()) : new ChannelResponse.Reply(answer, null);
        }
        if (message instanceof ChannelMessage.AuthorizationNeeded) {
            // YOUR DECISION: arranging authorization is out of band. A reply here is only a message
            // to the task and never carries a credential. Refusing is the safe default.
            return new ChannelResponse.Refuse("authorization is arranged by a person, not by this channel");
        }
        // YOUR DECISION: a link or file a remote offers. Fetching a link means making a request to an
        // address the remote chose: validate it as the transport does, or ignore it as here.
        return new ChannelResponse.Ignore();
    }
}
