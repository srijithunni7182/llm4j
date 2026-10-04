package io.github.llm4j.secret;

/** A secret was asked for on behalf of a host it is not allowed to be sent to. The message names the secret and the host, never the value. */
public class SecretAccessDeniedException extends SecretException {

    private final String name;
    private final String host;

    public SecretAccessDeniedException(String name, String host) {
        super("secret " + name + " may not be sent to " + host + "; allow the host in the secret's allowedHosts if that is intended");
        this.name = name;
        this.host = host;
    }

    public String name() {
        return name;
    }

    public String host() {
        return host;
    }
}
