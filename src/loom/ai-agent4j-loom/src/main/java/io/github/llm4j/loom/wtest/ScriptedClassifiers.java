package io.github.llm4j.loom.wtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.llm4j.classify.Answer;
import io.github.llm4j.classify.ChoiceAnswer;
import io.github.llm4j.classify.Classifier;
import io.github.llm4j.classify.ClassifyResult;
import io.github.llm4j.classify.ClassifyUsage;
import io.github.llm4j.classify.Question;
import io.github.llm4j.classify.ScoreAnswer;
import io.github.llm4j.classify.YesNoAnswer;
import io.github.llm4j.loom.execution.ClassifierFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * The classifiers of a test case: an {@code intentclassify} step is answered from the case, a label
 * for each question and an optional {@code confidence}, so routing on a classifier's answer can be
 * tested without a model. A question the case did not answer fails the case (strict), and so does a
 * label that is not one of the question's.
 */
final class ScriptedClassifiers implements ClassifierFactory {

    static final String PREFIX = "scripted:";
    private static final double DEFAULT_CONFIDENCE = 0.99;

    private final Map<String, JsonNode> scripts;
    private final Recorder recorder;
    private final Failures failures;

    ScriptedClassifiers(Map<String, JsonNode> scripts, Recorder recorder, Failures failures) {
        this.scripts = scripts;
        this.recorder = recorder;
        this.failures = failures;
    }

    @Override
    public Classifier create(String model, int samples) {
        String name = model.startsWith(PREFIX) ? model.substring(PREFIX.length()) : model;
        return request -> {
            int call = recorder.call(name);
            var fail = failures.failsOn(name, call);
            if (fail.isPresent()) throw Failures.model(fail.get(), name, call);
            JsonNode script = scripts.get(name);
            // a list answers the calls in order, the last one for every call after that
            if (script != null && script.isArray() && script.size() > 0)
                script = script.get(Math.min(call, script.size()) - 1);
            if (script == null || !script.isObject()) {
                String message =
                        "classifier " + name + " has no scripted answer (call " + call + ")";
                recorder.violation(message);
                throw new IllegalStateException(message);
            }
            JsonNode confidence = script.get("confidence");
            double sure = confidence == null ? DEFAULT_CONFIDENCE : confidence.asDouble();
            Map<String, Answer> answers = new LinkedHashMap<>();
            for (Question q : request.questions()) {
                JsonNode given = script.get(q.name());
                if (given == null) {
                    String message =
                            "classifier "
                                    + name
                                    + " has no scripted answer for the question "
                                    + q.name()
                                    + " (call "
                                    + call
                                    + ")";
                    recorder.violation(message);
                    throw new IllegalStateException(message);
                }
                answers.put(q.name(), answer(name, q, given.asText(), sure));
            }
            recorder.usage(10, 5);
            return new ClassifyResult(answers, new ClassifyUsage(10, 5, 0, "scripted", name));
        };
    }

    private Answer answer(String classifier, Question q, String value, double sure) {
        if (q instanceof Question.Choice c) {
            // "unsure" is what a low-confidence answer becomes when the classifier escalates, so a
            // case that scripts it gets the first label with no confidence at all
            if (value.equals("unsure"))
                return new ChoiceAnswer(c.labels().get(0), Map.of(), OptionalDouble.of(0.0), true);
            if (!c.labels().contains(value)) {
                String message =
                        "classifier "
                                + classifier
                                + " is scripted to answer "
                                + value
                                + " for "
                                + q.name()
                                + ", which is not one of "
                                + c.labels();
                recorder.violation(message);
                throw new IllegalStateException(message);
            }
            return new ChoiceAnswer(value, Map.of(), OptionalDouble.of(sure), true);
        }
        if (q instanceof Question.YesNo) {
            boolean yes = value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("true");
            return new YesNoAnswer(yes ? sure : 1 - sure, true);
        }
        Question.Score s = (Question.Score) q;
        int at = Math.max(0, s.scale().points().indexOf(value));
        double position =
                s.scale().points().size() == 1 ? 0 : (double) at / (s.scale().points().size() - 1);
        return new ScoreAnswer(
                position, s.scale().points().get(at), Map.of(), OptionalDouble.of(sure), true);
    }
}
