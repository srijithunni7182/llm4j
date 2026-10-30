package io.github.llm4j.loom.review.engine;

import io.github.llm4j.loom.review.model.Finding;
import java.util.regex.Pattern;

/**
 * Which findings are about the machine and not the script. A finding is a script finding only if
 * editing the script can clear it; a key that is not set in the environment is cleared by setting
 * it, whatever the script says, so it is a setup finding: listed apart, never counted as open and
 * never what {@code --fail-on} fails on (unless asked with {@code --include-setup}).
 */
public final class Setup {

    private static final Pattern ENVIRONMENT =
            Pattern.compile(
                    "needs \\S+ in the environment|is not set \\(and there is no secret of that name\\)");

    private Setup() {}

    /** True for a missing-input finding that only the person's machine can clear. */
    public static boolean is(Finding f) {
        return f.rule().equals("LV02") && ENVIRONMENT.matcher(f.title()).find();
    }
}
