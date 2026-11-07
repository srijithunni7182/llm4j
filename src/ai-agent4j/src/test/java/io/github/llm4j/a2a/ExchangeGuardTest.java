package io.github.llm4j.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ExchangeGuardTest {

    private static final ChannelMessage.Question QUESTION =
            new ChannelMessage.Question("Which date?", null, null, 1, null);

    private static ChannelContext context() {
        return new ChannelContext(
                "run-1",
                "step-1",
                "Buy",
                ChannelContext.Direction.INBOUND_QUESTION,
                "Supplier",
                null,
                3,
                null);
    }

    private static ExchangeGuard guard(int max) {
        return new ExchangeGuard(max, Duration.ofSeconds(5), 1000, ExchangeGuard.Waiter.direct());
    }

    private static final ExchangeGuard.ReplyValidator ACCEPT_ALL = reply -> Optional.empty();

    @Test
    void aReplyIsReturnedAndCountsAsOneQuestion() {
        var guard = guard(3);
        var response =
                guard.exchange(
                        (c, m) -> new ChannelResponse.Reply("Friday", null),
                        context(),
                        QUESTION,
                        ACCEPT_ALL);
        assertThat(response).isEqualTo(new ChannelResponse.Reply("Friday", null));
        assertThat(guard.questionsUsed()).isEqualTo(1);
    }

    @Test
    void theLimitHoldsAcrossCallsAndSoAcrossRetries() {
        var guard = guard(2);
        AgentChannel ch = (c, m) -> new ChannelResponse.Reply("x", null);
        guard.exchange(ch, context(), QUESTION, ACCEPT_ALL);
        guard.exchange(ch, context(), QUESTION, ACCEPT_ALL);
        assertThatThrownBy(() -> guard.exchange(ch, context(), QUESTION, ACCEPT_ALL))
                .isInstanceOf(A2AException.ChannelFailure.class)
                .hasMessageContaining("limit");
    }

    @Test
    void theChannelIsNotCalledOnceTheLimitIsSpent() {
        var guard = guard(1);
        var calls = new ArrayList<ChannelMessage>();
        AgentChannel ch =
                (c, m) -> {
                    calls.add(m);
                    return new ChannelResponse.Reply("x", null);
                };
        guard.exchange(ch, context(), QUESTION, ACCEPT_ALL);
        assertThatThrownBy(() -> guard.exchange(ch, context(), QUESTION, ACCEPT_ALL))
                .isInstanceOf(A2AException.class);
        assertThat(calls).hasSize(1);
    }

    @Test
    void theContextTellsTheChannelHowManyQuestionsAreLeft() {
        var guard = guard(3);
        var left = new ArrayList<Integer>();
        AgentChannel ch =
                (c, m) -> {
                    left.add(c.questionsLeft());
                    return new ChannelResponse.Reply("x", null);
                };
        guard.exchange(ch, context(), QUESTION, ACCEPT_ALL);
        guard.exchange(ch, context(), QUESTION, ACCEPT_ALL);
        assertThat(left).containsExactly(3, 2);
    }

    @Test
    void anInvalidReplyIsSentBackWithTheReasonAndCounted() {
        var guard = guard(3);
        var seen = new ArrayList<ChannelMessage>();
        AgentChannel ch =
                (c, m) -> {
                    seen.add(m);
                    return new ChannelResponse.Reply(seen.size() == 1 ? "bad" : "good", null);
                };
        var response =
                guard.exchange(
                        ch,
                        context(),
                        QUESTION,
                        r ->
                                r.text().equals("bad")
                                        ? Optional.of("must not be bad")
                                        : Optional.empty());
        assertThat(response).isEqualTo(new ChannelResponse.Reply("good", null));
        assertThat(guard.questionsUsed()).isEqualTo(2);
        var second = (ChannelMessage.Question) seen.get(1);
        assertThat(second.attempt()).isEqualTo(2);
        assertThat(second.rejectedBecause()).isEqualTo("must not be bad");
    }

    @Test
    void aReplyThatKeepsFailingValidationEndsInFailureWhenTheLimitIsSpent() {
        var guard = guard(2);
        assertThatThrownBy(
                        () ->
                                guard.exchange(
                                        (c, m) -> new ChannelResponse.Reply("bad", null),
                                        context(),
                                        QUESTION,
                                        r -> Optional.of("no")))
                .isInstanceOf(A2AException.ChannelFailure.class)
                .hasMessageContaining("limit");
        assertThat(guard.questionsUsed()).isEqualTo(2);
    }

    @Test
    void anAuthorizationRequestCountsAsAQuestionAndTakesOnlyAMessage() {
        var guard = guard(3);
        var response =
                guard.exchange(
                        (c, m) -> new ChannelResponse.Reply("done out of band", null),
                        context(),
                        new ChannelMessage.AuthorizationNeeded("sign in"),
                        ACCEPT_ALL);
        assertThat(response).isInstanceOf(ChannelResponse.Reply.class);
        assertThat(guard.questionsUsed()).isEqualTo(1);
    }

    @Test
    void aChannelThatThrowsFailsTheStepAndTheMessageHoldsNoCauseText() {
        var guard = guard(3);
        assertThatThrownBy(
                        () ->
                                guard.exchange(
                                        (c, m) -> {
                                            throw new IllegalStateException("secret-token-123");
                                        },
                                        context(),
                                        QUESTION,
                                        ACCEPT_ALL))
                .isInstanceOf(A2AException.ChannelFailure.class)
                .hasMessageNotContaining("secret-token-123")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void aChannelThatReturnsNothingFailsTheStep() {
        assertThatThrownBy(() -> guard(3).exchange((c, m) -> null, context(), QUESTION, ACCEPT_ALL))
                .isInstanceOf(A2AException.ChannelFailure.class)
                .hasMessageContaining("nothing");
    }

    @Test
    void aChannelThatNeverAnswersHitsTheRuntimesTimeout() {
        ExchangeGuard.Waiter timesOut =
                (call, timeout) -> {
                    throw new TimeoutException();
                };
        var guard = new ExchangeGuard(3, Duration.ofSeconds(5), 1000, timesOut);
        assertThatThrownBy(
                        () ->
                                guard.exchange(
                                        (c, m) -> new ChannelResponse.Reply("x", null),
                                        context(),
                                        QUESTION,
                                        ACCEPT_ALL))
                .isInstanceOf(A2AException.ChannelFailure.class)
                .hasMessageContaining("5");
    }

    @Test
    void theTimeoutTheWaiterIsGivenIsTheGuardsNotTheChannels() {
        var given = new ArrayList<Duration>();
        ExchangeGuard.Waiter spy =
                (call, timeout) -> {
                    given.add(timeout);
                    return call.call();
                };
        new ExchangeGuard(3, Duration.ofSeconds(7), 1000, spy)
                .exchange(
                        (c, m) -> new ChannelResponse.Reply("x", null),
                        context(),
                        QUESTION,
                        ACCEPT_ALL);
        assertThat(given).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void anOversizeReplyIsRefused() {
        var big = "x".repeat(1001);
        assertThatThrownBy(
                        () ->
                                guard(3).exchange(
                                                (c, m) -> new ChannelResponse.Reply(big, null),
                                                context(),
                                                QUESTION,
                                                ACCEPT_ALL))
                .isInstanceOf(A2AException.ChannelFailure.class)
                .hasMessageContaining("1000");
        var data = JsonNodeFactory.instance.objectNode().put("k", big);
        assertThatThrownBy(
                        () ->
                                guard(3).exchange(
                                                (c, m) -> new ChannelResponse.Reply("", data),
                                                context(),
                                                QUESTION,
                                                ACCEPT_ALL))
                .isInstanceOf(A2AException.ChannelFailure.class);
    }

    @Test
    void aResponseThatDoesNotFitTheMessageIsAFailureNeverDropped() {
        var file =
                new ChannelMessage.FileOffered.Link(
                        "https://f.example.com/a.pdf", "a", "application/pdf");
        assertThatThrownBy(
                        () ->
                                guard(3).exchange(
                                                (c, m) -> new ChannelResponse.Reply("sure", null),
                                                context(),
                                                file,
                                                ACCEPT_ALL))
                .isInstanceOf(A2AException.ChannelFailure.class);
        assertThatThrownBy(
                        () ->
                                guard(3).exchange(
                                                (c, m) -> new ChannelResponse.Ignore(),
                                                context(),
                                                QUESTION,
                                                ACCEPT_ALL))
                .isInstanceOf(A2AException.ChannelFailure.class);
    }

    @Test
    void aFileOfferCanBeIgnoredReceivedDeferredOrRefusedAndDoesNotCountAsAQuestion() {
        var guard = guard(1);
        var file =
                new ChannelMessage.FileOffered.Link(
                        "https://f.example.com/a.pdf", "a", "application/pdf");
        assertThat(
                        guard.exchange(
                                (c, m) -> new ChannelResponse.Ignore(),
                                context(),
                                file,
                                ACCEPT_ALL))
                .isInstanceOf(ChannelResponse.Ignore.class);
        assertThat(
                        guard.exchange(
                                (c, m) -> new ChannelResponse.Received(List.of()),
                                context(),
                                file,
                                ACCEPT_ALL))
                .isInstanceOf(ChannelResponse.Received.class);
        assertThat(
                        guard.exchange(
                                (c, m) -> new ChannelResponse.Refuse("no"),
                                context(),
                                file,
                                ACCEPT_ALL))
                .isInstanceOf(ChannelResponse.Refuse.class);
        assertThat(guard.questionsUsed()).isZero();
    }

    @Test
    void deferAndRefuseAreReturnedToTheCaller() {
        assertThat(
                        guard(3).exchange(
                                        (c, m) -> new ChannelResponse.Defer("tok-1"),
                                        context(),
                                        QUESTION,
                                        ACCEPT_ALL))
                .isEqualTo(new ChannelResponse.Defer("tok-1"));
        assertThat(
                        guard(3).exchange(
                                        (c, m) -> new ChannelResponse.Refuse("no"),
                                        context(),
                                        QUESTION,
                                        ACCEPT_ALL))
                .isEqualTo(new ChannelResponse.Refuse("no"));
    }

    @Test
    void theContextExposesNoVariablesAndMarksRemoteTextUntrusted() {
        var names =
                java.util.Arrays.stream(ChannelContext.class.getMethods())
                        .map(java.lang.reflect.Method::getName)
                        .toList();
        assertThat(names).doesNotContain("variables", "scope", "get");
        assertThat(QUESTION.toString()).contains("untrusted");
    }

    @Test
    void receiveFileFailsClosedWithNoSink() {
        assertThatThrownBy(() -> context().receiveFile("a", ""))
                .isInstanceOf(A2AException.Refused.class);
    }

    @Test
    void theLimitsAreValidated() {
        assertThatThrownBy(
                        () ->
                                new ExchangeGuard(
                                        0,
                                        Duration.ofSeconds(1),
                                        10,
                                        ExchangeGuard.Waiter.direct()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ExchangeGuard(
                                        1, Duration.ZERO, 10, ExchangeGuard.Waiter.direct()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new ExchangeGuard(
                                        1, Duration.ofSeconds(1), 0, ExchangeGuard.Waiter.direct()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
