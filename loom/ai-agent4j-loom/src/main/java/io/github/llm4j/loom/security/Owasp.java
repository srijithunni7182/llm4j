package io.github.llm4j.loom.security;

/** The OWASP Top 10 for Large Language Model Applications, 2025 edition (https://genai.owasp.org/llm-top-10/). */
public enum Owasp {
    LLM01("Prompt Injection"),
    LLM02("Sensitive Information Disclosure"),
    LLM03("Supply Chain"),
    LLM04("Data and Model Poisoning"),
    LLM05("Improper Output Handling"),
    LLM06("Excessive Agency"),
    LLM07("System Prompt Leakage"),
    LLM08("Vector and Embedding Weaknesses"),
    LLM09("Misinformation"),
    LLM10("Unbounded Consumption");

    private final String title;

    Owasp(String title) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    /** {@code LLM01:2025} */
    public String id() {
        return name() + ":2025";
    }
}
