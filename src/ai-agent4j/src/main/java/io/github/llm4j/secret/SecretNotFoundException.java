package io.github.llm4j.secret;

/** The store has no secret with this name. */
public class SecretNotFoundException extends SecretException {

    private final String name;

    public SecretNotFoundException(String name) {
        super("secret " + name + " is not in the store");
        this.name = name;
    }

    /** The name that was asked for. */
    public String name() {
        return name;
    }
}
