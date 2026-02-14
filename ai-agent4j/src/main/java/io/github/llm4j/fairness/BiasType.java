package io.github.llm4j.fairness;

/**
 * Types of bias that can be detected in AI systems.
 */
public enum BiasType {
    /**
     * Bias based on gender or gender identity.
     */
    GENDER("Gender Bias"),

    /**
     * Bias based on race or ethnicity.
     */
    RACIAL("Racial Bias"),

    /**
     * Bias based on age or generation.
     */
    AGE("Age Bias"),

    /**
     * Bias based on religious beliefs.
     */
    RELIGIOUS("Religious Bias"),

    /**
     * Bias based on nationality or geographic origin.
     */
    NATIONALITY("Nationality Bias"),

    /**
     * Bias based on socioeconomic status.
     */
    SOCIOECONOMIC("Socioeconomic Bias"),

    /**
     * Bias in language or tone that may be inappropriate.
     */
    LINGUISTIC("Linguistic Bias"),

    /**
     * Other forms of bias not categorized above.
     */
    OTHER("Other Bias");

    private final String displayName;

    BiasType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
