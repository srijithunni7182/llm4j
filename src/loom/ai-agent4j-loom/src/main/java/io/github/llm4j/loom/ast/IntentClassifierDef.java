package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code intentclassifier Intent { model: "Jev/jev-latest" choose kind: refund | other ask urgent:
 * "…" rate tone: calm | angry confidence at least 0.8 else escalate }}: a fixed set of typed
 * questions answered by a classifier backend (a chat model, or Jev) in one call. It has no system
 * prompt, no tools and no loop; that is the point of it (docs/specs/07-classifier, DESIGN 5).
 */
public class IntentClassifierDef implements Node {

    /**
     * The kinds of question: {@code choose} one label, {@code ask} yes/no, {@code rate} a point on
     * an ordered scale.
     */
    public enum Kind {
        CHOOSE,
        ASK,
        RATE
    }

    /**
     * What a {@code confidence at least} rule does when the lowest confidence is below the
     * threshold.
     */
    public enum Action {
        /** The label becomes {@link #UNSURE}. */
        ESCALATE,
        /** A person is asked, and their answer is the label. */
        ASK_PERSON,
        /** The step fails, as a failing task does. */
        FAIL
    }

    /**
     * The reserved label a {@code confidence … else escalate} rule produces. It is never declared,
     * and a backend never produces it.
     */
    public static final String UNSURE = "unsure";

    /** Fields of the bound record that a question name must not shadow. */
    public static final List<String> RESERVED_NAMES =
            List.of("confidence", "calibrated", "backend", "model");

    /**
     * One question. {@code instructions} is null when the script did not write them (see {@link
     * #effectiveInstructions}); {@code labels} are the labels of a {@code choose} or the points of
     * a {@code rate}, in order, and empty for an {@code ask}.
     */
    public record Question(
            Kind kind, String name, String instructions, List<String> labels, int line) {}

    /** {@code confidence at least 0.8 else escalate}. */
    public record Confidence(double threshold, Action action, int line) {}

    private final String name;
    private int line;
    private String model;
    private int modelLine;
    private int samples = 1;
    private int samplesLine;
    private final List<Question> questions = new ArrayList<>();
    private Confidence confidence;
    private String pii;
    private int piiLine;

    public IntentClassifierDef(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public String getModel() {
        return model;
    }

    public int getModelLine() {
        return modelLine;
    }

    public void setModel(String model, int line) {
        this.model = model;
        this.modelLine = line;
    }

    public int getSamples() {
        return samples;
    }

    public int getSamplesLine() {
        return samplesLine;
    }

    public void setSamples(int samples, int line) {
        this.samples = samples;
        this.samplesLine = line;
    }

    public List<Question> getQuestions() {
        return questions;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    /**
     * {@code pii: mask | block | warn}: what to do with personal data in the state before it
     * leaves, or null for nothing.
     */
    public String getPii() {
        return pii;
    }

    public int getPiiLine() {
        return piiLine;
    }

    public void setPii(String pii, int line) {
        this.pii = pii;
        this.piiLine = line;
    }

    public void setConfidence(Confidence confidence) {
        this.confidence = confidence;
    }

    /** The question's instructions as written, or a sentence built from its name. */
    public String effectiveInstructions(Question q) {
        if (q.instructions() != null && !q.instructions().isBlank()) return q.instructions();
        return switch (q.kind()) {
            case CHOOSE -> "Which " + q.name() + " fits best?";
            case ASK -> "Is it " + q.name() + "? Answer yes or no.";
            case RATE -> "Where on the scale is the " + q.name() + "?";
        };
    }

    /**
     * The labels a {@code choose} question can produce: its own, and {@link #UNSURE} when an
     * escalating confidence rule is present.
     */
    public List<String> possibleLabels(Question q) {
        List<String> out = new ArrayList<>(q.labels());
        if (q.kind() == Kind.CHOOSE && confidence != null && confidence.action() == Action.ESCALATE)
            out.add(UNSURE);
        return out;
    }

    /** The question called {@code name}, or null. */
    public Question question(String name) {
        return questions.stream().filter(q -> q.name().equals(name)).findFirst().orElse(null);
    }
}
