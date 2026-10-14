package io.github.llm4j.context;

class ChainContractTest extends ContextCompactorContract {
    @Override
    protected ContextCompactor subject() {
        return ContextCompactors.chain(ContextCompactors.trimToolResults(500), ContextCompactors.dropOldest());
    }
}
