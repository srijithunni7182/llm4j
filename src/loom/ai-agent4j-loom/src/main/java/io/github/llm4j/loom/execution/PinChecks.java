package io.github.llm4j.loom.execution;

import io.github.llm4j.loom.ast.ToolDef;
import io.github.llm4j.tools.openapi.ApiDescription;
import io.github.llm4j.tools.openapi.SpecLoader;
import io.github.llm4j.tools.openapi.SpecPin;
import java.util.HashMap;
import java.util.Map;

/**
 * The offline half of drift detection (phase 36a, D12; audit rule LA35): an {@code openapi} tool's
 * {@code pin:} is compared with the operation's signature in the description when that is a file
 * beside the script. A description that is an address, or cannot be read here, is not guessed at:
 * {@code weave connect --refresh --check} reads those.
 */
final class PinChecks {

    private PinChecks() {}

    static void run(ScriptValidator.Checker c) {
        Map<String, ApiDescription> read = new HashMap<>();
        for (ToolDef t : c.script().getTools()) {
            if (!"openapi".equals(t.getKind())) continue;
            ToolDef.OptionValue pin = t.getOptions().get("pin");
            ToolDef.OptionValue operation = t.getOptions().get("operation");
            ToolDef.OptionValue spec = t.getOptions().get("spec");
            if (pin == null || operation == null || spec == null || spec.isReference()) continue;
            if (spec.value().matches("(?i)https?://.*")) continue;
            ApiDescription description =
                    read.computeIfAbsent(spec.value(), where -> load(c, where));
            if (description == null) continue;
            var found = description.operation(operation.value());
            String message;
            if (found.isEmpty())
                message =
                        "pin: "
                                + operation.value()
                                + " is no longer an operation of "
                                + spec.value()
                                + " (LA35); run weave connect --refresh to see what changed";
            else if (!SpecPin.of(found.get()).equals(pin.value()))
                message =
                        "pin: the signature of "
                                + operation.value()
                                + " in "
                                + spec.value()
                                + " is not the one that was pinned (LA35); review what changed and run"
                                + " weave connect --refresh to pin it again";
            else continue;
            c.warn(t.getLine(), "tool " + t.getName(), message);
        }
    }

    private static ApiDescription load(ScriptValidator.Checker c, String where) {
        try {
            return SpecLoader.load(where, c.context().baseDir, SpecLoader.Fetcher.NONE);
        } catch (RuntimeException unreadable) {
            return null; // the tool's own check reports a description that cannot be read
        }
    }
}
