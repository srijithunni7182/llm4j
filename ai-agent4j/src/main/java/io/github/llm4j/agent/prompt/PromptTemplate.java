package io.github.llm4j.agent.prompt;

import java.util.Collections;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Represents a single version of a prompt template.
 * Supports mustache-style variable substitution {{variable}}.
 */
public class PromptTemplate {
    private final String id;
    private final String version;
    private final String template;
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}]+)}}");

    public PromptTemplate(String id, String version, String template) {
        this.id = id;
        this.version = version;
        this.template = template;
    }

    public String getId() {
        return id;
    }

    public String getVersion() {
        return version;
    }

    public String getTemplate() {
        return template;
    }

    /**
     * Renders the template by substituting variables.
     * 
     * @param variables Map of variable names to values
     * @return Rendered string
     */
    public String render(Map<String, Object> variables) {
        if (template == null || template.isEmpty()) {
            return "";
        }

        if (variables == null) {
            variables = Collections.emptyMap();
        }

        StringBuilder sb = new StringBuilder();
        Matcher matcher = VARIABLE_PATTERN.matcher(template);

        while (matcher.find()) {
            String varName = matcher.group(1).trim();
            Object value = variables.get(varName);

            // If variable missing, keep original text or replace with empty?
            // Standard approach: keep original tag if missing is safer for debugging,
            // or replace with empty string. Let's retry keeping meaningful error or empty.
            // For now, let's use empty string for missing values to be clean,
            // or string representation of value.
            String replacement = value != null ? String.valueOf(value) : "";
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    public String render() {
        return render(Collections.emptyMap());
    }
}
