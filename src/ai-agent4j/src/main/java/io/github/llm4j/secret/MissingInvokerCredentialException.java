package io.github.llm4j.secret;

import java.util.List;

/** A run needs credentials of the person who started it, and was started without them. The message names credentials, never values. */
public class MissingInvokerCredentialException extends SecretException {

    private final List<String> names;

    public MissingInvokerCredentialException(List<String> names) {
        super("this workflow acts as the person who runs it and needs invoker credentials " + names
                + ", but the run was started without them (supply InvokerCredentials to executeWorkflow)");
        this.names = List.copyOf(names);
    }

    /** The credentials that are missing. */
    public List<String> names() {
        return names;
    }
}
