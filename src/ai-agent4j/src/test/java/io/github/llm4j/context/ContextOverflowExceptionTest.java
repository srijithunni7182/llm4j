package io.github.llm4j.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.exception.ContextOverflowException;
import io.github.llm4j.exception.InvalidRequestException;
import io.github.llm4j.exception.LLMException;
import org.junit.jupiter.api.Test;

class ContextOverflowExceptionTest {

    @Test
    void existingHandlersForBadRequestsStillCatchIt() {
        Throwable t = new ContextOverflowException("too long", 400, "{}");
        assertThat(t).isInstanceOf(InvalidRequestException.class).isInstanceOf(LLMException.class);
    }

    @Test
    void itCarriesWhatTheProviderSaid() {
        ContextOverflowException e = new ContextOverflowException("too long", 413, "body");
        assertThat(e.getStatusCode()).isEqualTo(413);
        assertThat(e.getResponseBody()).isEqualTo("body");
        assertThat(e.getMessage()).isEqualTo("too long");
    }

    @Test
    void itCanBeRaisedByTheAgentItselfWithNoProviderInvolved() {
        ContextOverflowException e = new ContextOverflowException("the prompt cannot fit");
        assertThat(e.getStatusCode()).isEqualTo(400);
        assertThat(e.getResponseBody()).isNull();
    }
}
