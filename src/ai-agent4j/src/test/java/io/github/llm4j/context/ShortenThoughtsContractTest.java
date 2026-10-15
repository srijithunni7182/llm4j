package io.github.llm4j.context;

/** The class written in the documentation is held to the same contract as the built-ins. */
class ShortenThoughtsContractTest extends ContextCompactorContract {
    @Override
    protected ContextCompactor subject() {
        return new ContextDocsExamplesTest.ShortenThoughts();
    }
}
