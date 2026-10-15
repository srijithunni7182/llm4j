package io.github.llm4j.context;

class TrimToolResultsContractTest extends ContextCompactorContract {
    @Override
    protected ContextCompactor subject() {
        return ContextCompactors.trimToolResults(500);
    }
}
