package io.github.llm4j.loom.cli;

import io.github.llm4j.secret.InvokerCredentials;
import io.github.llm4j.secret.SecretNames;
import java.util.LinkedHashMap;
import java.util.Map;
import picocli.CommandLine.Option;

/**
 * {@code --invoker-env NAME=VARIABLE}: the credential a script asks for as {@code invoker.NAME} is the value of an environment variable. It lets a
 * person run, from a terminal, a workflow that a web application would run for its users. The variable is read again for every request, so a
 * wrapper that keeps it fresh works; the credential is never written to the run directory, which is why {@code weave resume} takes the option too.
 */
final class InvokerOptions {

    @Option(names = "--invoker-env", paramLabel = "NAME=VARIABLE",
            description = "Supply invoker.NAME from an environment variable, e.g. --invoker-env token=MY_API_TOKEN (repeatable).")
    Map<String, String> variables = new LinkedHashMap<>();

    /** The environment with these credentials, or null (after saying why) when an option is not valid. */
    WeaveEnv apply(WeaveEnv env) {
        if (variables.isEmpty()) return env;
        for (Map.Entry<String, String> e : variables.entrySet()) {
            if (!SecretNames.isValid(e.getKey())) {
                env.err().println("Error: --invoker-env " + e.getKey() + "=" + e.getValue() + ": a name is a letter followed by letters, digits, _ or -");
                return null;
            }
            if (e.getValue() == null || e.getValue().isBlank()) {
                env.err().println("Error: --invoker-env " + e.getKey() + " needs the name of an environment variable");
                return null;
            }
        }
        return env.withInvoker(new FromVariables(Map.copyOf(variables), env.env()));
    }

    /** Credentials read from environment variables, each time they are asked for. {@link #toString()} shows names only. */
    private record FromVariables(Map<String, String> variables, java.util.function.Function<String, String> lookup) implements InvokerCredentials {

        @Override
        public String get(String name) {
            String variable = variables.get(name);
            String value = variable == null ? null : lookup.apply(variable);
            return value == null || value.isBlank() ? null : value;
        }

        @Override
        public boolean provides(String name) {
            return get(name) != null;
        }

        @Override
        public String toString() {
            return "InvokerCredentials" + variables.keySet();
        }
    }
}
