package io.github.llm4j.loom.review.adapt;

import java.util.regex.Pattern;

/**
 * The kinds of problem {@code weave check} reports, each with a stable rule id. A problem is
 * claimed by the first class whose pattern matches its message; what no class claims stays {@code
 * LV00}. Severity is not part of the class: an error stays high and a warning low.
 */
public enum ValidityClass {
    UNKNOWN_NAME(
            "LV01",
            "A name is used that is not defined",
            "An agent, tool, task or workflow is named that the script does not define, so the step cannot run.",
            "Use a name that is defined (the message lists them), or define the missing one.",
            "there is no \\w+ named|no such \\w+|is not defined|\\w+ not found"),
    MISSING_INPUT(
            "LV02",
            "A file, secret or environment value is missing",
            "A prompt file, secret, environment variable or source the script depends on is not there, so it fails before it starts or at the first use.",
            "Create the file, set the secret or variable, or point the script at the right one (weave secret).",
            "prompt files|secret|is not set|in the environment|must come from the environment|does not exist"),
    UNUSED_RESULT(
            "LV03",
            "A result is set but never used",
            "The step's answer is bound to a name that nothing reads, which usually means the answer is thrown away or a later step was forgotten.",
            "Use the result (in a note, a condition or a later step) or remove the binding.",
            "never used"),
    BAD_FIELD(
            "LV04",
            "The script reads something the answer does not have",
            "A condition or placeholder reads a field the answer does not have, or the answer is not in the shape it is read as, so the step misbehaves.",
            "Use a field the answer has, or change the output schema so it has this one.",
            "has no field|the answer is not|the answer is read as"),
    DEAD_ANSWER(
            "LV05",
            "A human's answer changes nothing",
            "A person is asked, but the answer is never read, so the workflow does the same whatever they say.",
            "Use the answer in a condition or step, or remove the question.",
            "never read"),
    UNREACHABLE(
            "LV06",
            "A statement can never run",
            "Nothing can reach this statement, so it does nothing.",
            "Delete it, or fix the condition that keeps it from running.",
            "cannot be reached|can never happen"),
    DUPLICATE(
            "LV07",
            "Something is defined twice",
            "Two definitions share a name, so one of them silently wins or the script is ambiguous.",
            "Rename or remove one of them.",
            "declared twice|defined twice"),
    SETTING(
            "LV09",
            "A setting is wrong, missing or has no effect",
            "An option has a value the engine does not accept, a required option is missing, or two options contradict each other.",
            "Change the setting as the message says; weave check explains each one.",
            "unknown (option|use|strategy|guardrail|provider)|^needs |\\bneeds \\w|must be|must not|only applies|without streaming|has no effect|takes \\w|names no model|is required");

    private final String id;
    private final String title;
    private final String why;
    private final String fixHint;
    private final Pattern pattern;

    ValidityClass(String id, String title, String why, String fixHint, String regex) {
        this.id = id;
        this.title = title;
        this.why = why;
        this.fixHint = fixHint;
        this.pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
    }

    public String id() {
        return id;
    }

    public String title() {
        return title;
    }

    public String why() {
        return why;
    }

    public String fixHint() {
        return fixHint;
    }

    /** The class that claims {@code message} (the construct is not used), or {@code null}. */
    public static ValidityClass of(String message) {
        // order matters: a dead human answer also says "never", and a name that is missing is
        // checked before a missing input
        for (ValidityClass c :
                new ValidityClass[] {
                    DEAD_ANSWER,
                    UNUSED_RESULT,
                    UNREACHABLE,
                    DUPLICATE,
                    BAD_FIELD,
                    UNKNOWN_NAME,
                    MISSING_INPUT,
                    SETTING
                }) if (c.pattern.matcher(message).find()) return c;
        return null;
    }
}
