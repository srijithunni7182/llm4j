package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.secret.InvokerCredentials;
import io.github.llm4j.secret.MissingInvokerCredentialException;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/** The credentials of the person running a workflow that a script asks for ({@code invoker.NAME}), and the check that a run has them. */
public final class InvokerRefs {

    private InvokerRefs() { }

    /** The names a script refers to as {@code invoker.NAME}, sorted. */
    public static SortedSet<String> names(LoomScript script) {
        SortedSet<String> names = new TreeSet<>();
        for (ToolDef tool : script.getTools()) {
            tool.getOptions().values().stream().filter(ToolDef.OptionValue::fromInvoker).forEach(v -> names.add(v.value()));
        }
        return names;
    }

    /**
     * Stops a run that was started without what its script needs, before it does anything.
     *
     * @throws MissingInvokerCredentialException naming every credential {@code invoker} cannot supply
     */
    public static void require(LoomScript script, InvokerCredentials invoker) {
        List<String> missing = names(script).stream().filter(name -> !invoker.provides(name)).toList();
        if (!missing.isEmpty()) throw new MissingInvokerCredentialException(missing);
    }
}
