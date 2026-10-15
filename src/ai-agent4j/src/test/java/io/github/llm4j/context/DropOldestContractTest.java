package io.github.llm4j.context;

class DropOldestContractTest extends ContextCompactorContract {
    @Override
    protected ContextCompactor subject() {
        return ContextCompactors.dropOldest();
    }
}
