package io.github.llm4j.classify;

import io.github.llm4j.classify.fixtures.ScriptedChat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** {@link LlmClassifier} meets the {@link ClassifierContract}. */
class LlmClassifierContractTest extends ClassifierContract {

    private ScriptedChat chat;

    @Override
    protected Classifier answering(List<A> answers) {
        chat = new ScriptedChat().reply(json(answers));
        return LlmClassifier.builder(chat).build();
    }

    @Override
    protected int callsSent() {
        return chat.requests().size();
    }

    @Override
    protected boolean calibrated() {
        return false;
    }

    static String json(List<A> answers) {
        return "{\"answers\": {"
                + answers.stream()
                        .map(LlmClassifierContractTest::one)
                        .collect(Collectors.joining(", "))
                + "}}";
    }

    private static String one(A a) {
        String conf =
                a.confidence() == null
                        ? ""
                        : String.format(Locale.ROOT, ", \"confidence\": %s", a.confidence());
        return switch (a.kind()) {
            case CHOICE -> String.format(
                    "\"%s\": {\"label\": \"%s\"%s}", a.name(), a.label(), conf);
            case SCORE -> String.format("\"%s\": {\"point\": \"%s\"%s}", a.name(), a.label(), conf);
            case YES_NO -> String.format(
                    Locale.ROOT,
                    "\"%s\": {\"answer\": \"%s\", \"confidence\": %s}",
                    a.name(),
                    a.number() >= 0.5 ? "yes" : "no",
                    a.number() >= 0.5 ? a.number() : 1 - a.number());
        };
    }
}
