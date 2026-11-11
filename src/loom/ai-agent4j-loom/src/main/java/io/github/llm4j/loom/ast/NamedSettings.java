package io.github.llm4j.loom.ast;

/** A top-level declaration written as {@code keyword Name { key: value … }}: the name and the settings. */
public abstract class NamedSettings extends Settings {

    private final String name;

    protected NamedSettings(String name) {
        this.name = name;
    }

    /** The declared name. */
    public String getName() {
        return name;
    }
}
