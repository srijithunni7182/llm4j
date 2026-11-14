package io.github.llm4j.tools;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.ToolProfile;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * {@code tool Calc { use: compute }}: exact arithmetic, dates, a value out of JSON and statistics
 * over CSV, computed from the arguments alone (phase 35, D13). It reaches nothing and changes
 * nothing, so it needs no approval and replays exactly; a date is read from the run's clock.
 */
public final class ComputeKind extends GenericKind {

    static final long DEFAULT_MAX_BYTES = 1024 * 1024;
    static final long MAX_BYTES_CEILING = 8L * 1024 * 1024;
    static final int DEFAULT_MAX_ROWS = 100_000;
    static final int MAX_ROWS_CEILING = 1_000_000;

    @Override
    public String name() {
        return "compute";
    }

    @Override
    public Set<String> optional() {
        return Set.of("max_bytes", "max_rows");
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        o.size("max_bytes", DEFAULT_MAX_BYTES, MAX_BYTES_CEILING);
        o.integer("max_rows", DEFAULT_MAX_ROWS, 1, MAX_ROWS_CEILING);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        return new ComputeTool(
                name,
                o.size("max_bytes", DEFAULT_MAX_BYTES, MAX_BYTES_CEILING),
                o.integer("max_rows", DEFAULT_MAX_ROWS, 1, MAX_ROWS_CEILING),
                context);
    }

    /** Pure: computes from its arguments only, so a host passes their origin on to the result. */
    @Override
    public ToolProfile profile(Map<String, String> options) {
        return ToolProfile.pure("computes from its arguments only (calc, date, json, csv)");
    }
}
