package io.github.llm4j.eval.testing;

import io.github.llm4j.classify.Answer;
import io.github.llm4j.classify.ChoiceAnswer;
import io.github.llm4j.classify.ClassificationException;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyRequest;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.classify.Question;
import io.github.llm4j.classify.ScoreAnswer;
import io.github.llm4j.classify.YesNoAnswer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link Classifier} that answers from a script and never calls a model, so a judge, a Loom workflow or an evaluation can be tested for
 * free and exactly. It answers each question by name; a question it was not scripted for is an error, so a test cannot pass by accident. It
 * checks what it returns the way every real backend does: a label that the request did not offer is a {@link ClassificationException}.
 *
 * <pre>{@code
 * Classifier c = ScriptedClassifier.builder()
 *         .choice("intent", "refund", 0.93)
 *         .yesNo("urgent", 0.12)
 *         .build();
 * }</pre>
 *
 * <p>Several calls can be scripted with {@link Builder#next()}; calls past the last script repeat it. Every request is kept in
 * {@link #requests()}. Thread-safe.
 */
public final class ScriptedClassifier implements Classifier {

    private record Scripted(String kind, String label, double number, OptionalDouble confidence) {}

    private final List<Map<String, Scripted>> calls;
    private final boolean calibrated;
    private final ClassifyUsage usage;
    private final List<RuntimeException> failures;
    private final List<ClassifyRequest> requests = new CopyOnWriteArrayList<>();
    private int next;

    private ScriptedClassifier(Builder b) {
        this.calls = b.calls;
        this.calibrated = b.calibrated;
        this.usage = b.usage;
        this.failures = b.failures;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The requests received, in order. */
    public List<ClassifyRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public synchronized ClassifyResult classify(ClassifyRequest request) {
        requests.add(request);
        int call = next++;
        if (call < failures.size() && failures.get(call) != null) throw failures.get(call);
        Map<String, Scripted> script = calls.get(Math.min(call, calls.size() - 1));
        Map<String, Answer> answers = new LinkedHashMap<>();
        for (Question q : request.questions()) {
            Scripted s = script.get(q.name());
            if (s == null) throw new IllegalStateException("ScriptedClassifier has no scripted answer for question '" + q.name() + "'");
            answers.put(q.name(), answer(q, s));
        }
        return new ClassifyResult(answers, usage);
    }

    private Answer answer(Question q, Scripted s) {
        if (q instanceof Question.Choice c) {
            if (!s.kind().equals("choice")) throw wrong(q, "a choice");
            if (!c.labels().contains(s.label())) {
                throw new ClassificationException("question '" + q.name() + "' was scripted with '" + s.label() + "', which is not one of " + c.labels());
            }
            return new ChoiceAnswer(s.label(), Map.of(), s.confidence(), calibrated);
        } else if (q instanceof Question.YesNo) {
            if (!s.kind().equals("yesno")) throw wrong(q, "a yes/no");
            return new YesNoAnswer(s.number(), calibrated);
        }
        if (!s.kind().equals("score")) throw wrong(q, "a score");
        Question.Score sc = (Question.Score) q;
        return new ScoreAnswer(s.number(), sc.scale().nearest(s.number()), Map.of(), s.confidence(), calibrated);
    }

    private static ClassificationException wrong(Question q, String expected) {
        return new ClassificationException("question '" + q.name() + "' was scripted as the wrong kind; it needs " + expected + " answer");
    }

    /** Scripts a {@link ScriptedClassifier}. */
    public static final class Builder {

        private final List<Map<String, Scripted>> calls = new ArrayList<>(List.of(new LinkedHashMap<>()));
        private final List<RuntimeException> failures = new ArrayList<>();
        private boolean calibrated;
        private ClassifyUsage usage = new ClassifyUsage(0, 0, 0, "scripted", "scripted");

        private Builder() {}

        private Map<String, Scripted> current() {
            return calls.get(calls.size() - 1);
        }

        /** Answers a choice question with this label and no confidence. */
        public Builder choice(String question, String label) {
            current().put(question, new Scripted("choice", label, 0, OptionalDouble.empty()));
            return this;
        }

        public Builder choice(String question, String label, double confidence) {
            current().put(question, new Scripted("choice", label, 0, OptionalDouble.of(confidence)));
            return this;
        }

        /** Answers a yes/no question with this probability of yes. */
        public Builder yesNo(String question, double probability) {
            current().put(question, new Scripted("yesno", null, probability, OptionalDouble.empty()));
            return this;
        }

        /** Answers a score question with this position from 0.0 to 1.0 along its scale. */
        public Builder score(String question, double position) {
            current().put(question, new Scripted("score", null, position, OptionalDouble.empty()));
            return this;
        }

        /** Ends the script of one call; what follows is the script of the next call. */
        public Builder next() {
            calls.add(new LinkedHashMap<>());
            return this;
        }

        /** The call being scripted throws this instead of answering. */
        public Builder failWith(RuntimeException e) {
            while (failures.size() < calls.size() - 1) failures.add(null);
            failures.add(e);
            return this;
        }

        /** Marks the answers calibrated, to test code that treats a calibrated backend differently. Default false. */
        public Builder calibrated(boolean calibrated) {
            this.calibrated = calibrated;
            return this;
        }

        /** The usage every result reports. Default: zero tokens, backend {@code "scripted"}. */
        public Builder usage(ClassifyUsage usage) {
            this.usage = usage;
            return this;
        }

        public ScriptedClassifier build() {
            return new ScriptedClassifier(this);
        }
    }
}
