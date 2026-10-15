package io.github.llm4j.context;

class NoneContractTest extends ContextCompactorContract {
    @Override
    protected ContextCompactor subject() {
        return ContextCompactors.none();
    }
}
