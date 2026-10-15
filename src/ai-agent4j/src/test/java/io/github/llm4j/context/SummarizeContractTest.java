package io.github.llm4j.context;

/** The summarising strategy obeys the same rules as every other (a scripted model stands in for the real one). */
class SummarizeContractTest extends ContextCompactorContract {
    @Override
    protected ContextCompactor subject() {
        return ContextCompactors.summarize(SummariserModel.saying("Looked at several pages; found the price (500) and the plan name (Pro)."));
    }
}
