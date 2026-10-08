package io.github.llm4j.nirmaan.agent;

import io.github.llm4j.nirmaan.model.ProjectContext;
import io.github.llm4j.nirmaan.model.ProjectStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DhruvAgent extends BaseNirmaanAgent {

    @Override
    public String getName() {
        return "Dhruv";
    }

    @Override
    public String getRole() {
        return "Build Engineer";
    }

    @Override
    public void execute(ProjectContext context) {
        // Default to build (legacy support)
        build(context);
    }

    public boolean build(ProjectContext context) {
        logThought(context,
                "I will trigger the build process using the command defined in the Spec to ensure the code compiles.");
        return executeCommand(context, "Build Command", ProjectStatus.BUILDING);
    }

    public boolean runTests(ProjectContext context) {
        logThought(context,
                "I will run the unit tests. If they fail, I will report the errors back to Vihaan for fixing.");
        return executeCommand(context, "Test Command", ProjectStatus.TESTING);
    }

    private boolean executeCommand(ProjectContext context, String commandKey, ProjectStatus status) {
        context.setStatus(status);

        String specContent = context.getArtifacts().get("SPEC.md");
        if (specContent == null)
            return false;

        String command = extractCommand(specContent, commandKey);

        if (command == null || command.isBlank() || command.equalsIgnoreCase("None")) {
            context.log(getName(), "No " + commandKey + " found. Skipping.");
            return true; // Treat as pass if no command
        }

        context.log(getName(), "Executing " + commandKey + ": " + command);
        StringBuilder fullLog = new StringBuilder();

        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", command);
            pb.directory(context.getSandboxPath().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    fullLog.append(line).append("\n");
                    if (containsKeyWords(line))
                        context.log(getName(), line);
                }
            }

            // Save the log for Vihaan to analyze
            context.addArtifact("LAST_BUILD_LOG.txt", fullLog.toString());

            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroy();
                context.log(getName(), "Time Limit Exceeded.");
                return false;
            }

            int exitCode = process.exitValue();
            context.log(getName(), commandKey + (exitCode == 0 ? " SUCCEEDED." : " FAILED (Code " + exitCode + ")."));
            return exitCode == 0;

        } catch (Exception e) {
            context.log(getName(), "System Error: " + e.getMessage());
            context.addArtifact("LAST_BUILD_LOG.txt", "System Error: " + e.getMessage());
            return false;
        }
    }

    private String extractCommand(String text, String key) {
        if (text == null)
            return null;

        // Regex Explanation:
        // (?im) -> Case insensitive, Multiline mode
        // ^ -> Start of line
        // [*_#\-\s]* -> Optional markdown/whitespace (e.g. "**", "- ", "# ")
        // \Qkey\E -> The literal key
        // [*_]* -> Optional closing markdown (e.g. "**")
        // \s*[:]\s* -> Colon and whitespace
        // (.*)$ -> Capture the rest of the line as the value
        // Regex Explanation:
        // (?im) -> Case insensitive, Multiline mode
        // ^ -> Start of line
        // [*_#\-\s\d\.]* -> Optional markdown/whitespace/numbers (e.g. "**", "1. ", "##
        // ")
        // \Qkey\E -> The literal key
        // [*_]* -> Optional closing markdown (e.g. "**")
        // \s*[:]?\s* -> Optional Colon and whitespace
        // (.*)$ -> Capture the rest of the line as the value
        Pattern pattern = Pattern.compile("(?im)^[*_#\\-\\s\\d\\.]*" + Pattern.quote(key) + "[*_]*\\s*[:]?\\s*(.*)$");
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            String value = matcher.group(1).trim();
            // If the value is empty or looks like a placeholder, check the next line
            if (isValueEmpty(value)) {
                // Look for the next non-empty line
                int matchEnd = matcher.end();
                String remainingUser = text.substring(matchEnd);
                for (String line : remainingUser.split("\n")) {
                    if (!line.trim().isEmpty()) {
                        value = line.trim();
                        break;
                    }
                }
            }
            return cleanCommand(value);
        }
        return null;
    }

    private boolean isValueEmpty(String value) {
        return value.isEmpty() || value.matches("^[*_`\\s]*$") || value.equalsIgnoreCase("None");
    }

    private String cleanCommand(String value) {
        // Remove markdown code blocks (``` or ```bash)
        value = value.replaceAll("```[a-z]*", "").replaceAll("```", "");
        // Remove inline code markers
        value = value.replaceAll("^`+|`+$", "");
        // Remove quotes
        value = value.replaceAll("^['\"]+|['\"]+$", "");
        // Remove markdown bold/italic
        value = value.replaceAll("^[*_]+|[*_]+$", "");

        return value.trim();
    }

    private boolean containsKeyWords(String line) {
        String l = line.toLowerCase();
        return l.contains("error") || l.contains("fail") || l.contains("success") ||
                l.contains("build") || l.contains("test") || l.contains("run");
    }
}
