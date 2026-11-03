package io.github.llm4j.loom.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code extractor Name { model: "…" instructions: "…" fields { … } optional: [ … ] }}: a stateless
 * model step that pulls typed fields out of the text it is handed and nothing else
 * (docs/specs/24-stateless-nodes, R1). It has no tools, no memory, no persona, no skills and no
 * loop; that is the point of it. It reads neither the run transcript nor the script's shared prompt.
 */
public final class ExtractorDef implements Node {

    private final String name;
    private int line;
    private String model;
    private String instructions;
    private String promptRef;
    private SchemaDef fields;
    private final List<String> optional = new ArrayList<>();
    private String pii;
    private double temperature;
    private long timeoutMillis;
    private BudgetDef budget;

    /** Creates an extractor called {@code name}. */
    public ExtractorDef(String name) {
        this.name = name;
    }

    /** The extractor's name, as written after {@code extractor} and in {@code extract}. */
    public String getName() {
        return name;
    }

    /** The 1-based line of the declaration, or 0 when not built from a script. */
    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    /** The model (or alias) that does the extraction. */
    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    /** The fixed instructions, or null when none were written or they come from a prompt file. */
    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    /** The id of the prompt file the instructions come from, or null. */
    public String getPromptRef() {
        return promptRef;
    }

    public void setPromptRef(String promptRef) {
        this.promptRef = promptRef;
    }

    /** The fields, as an object schema in the order they were written. */
    public SchemaDef getFields() {
        return fields;
    }

    public void setFields(SchemaDef fields) {
        this.fields = fields;
    }

    /** The names of the top-level fields that may be absent. */
    public List<String> getOptional() {
        return optional;
    }

    /** {@code mask}, {@code block} or {@code warn}, or null when the setting was not written. */
    public String getPii() {
        return pii;
    }

    public void setPii(String pii) {
        this.pii = pii;
    }

    /** The sampling temperature; {@code 0} unless the script says otherwise. */
    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    /** A time limit for one call in milliseconds, or 0 for none. */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /** The extractor's own budget, or null. */
    public BudgetDef getBudget() {
        return budget;
    }

    public void setBudget(BudgetDef budget) {
        this.budget = budget;
    }

    /** True when the field called {@code field} may be absent. */
    public boolean isOptional(String field) {
        return optional.contains(field);
    }
}
