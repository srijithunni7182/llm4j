package io.github.llm4j.loom.generate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The files in a model's reply: {@code === file: path ===}, the text, {@code === end ===}. */
public final class FileBlocks {

    private FileBlocks() {}

    private static final Pattern BLOCK =
            Pattern.compile("(?ms)^=== file: (.+?) ===[ \\t]*\\R(.*?)^=== ?end(?: ?===?)?[ \\t]*$");

    /**
     * Every file in the reply, in order; empty when it holds none. A path written twice keeps the
     * last.
     */
    public static Map<String, String> parse(String reply) {
        Map<String, String> out = new LinkedHashMap<>();
        if (reply == null) return out;
        Matcher m = BLOCK.matcher(reply);
        while (m.find()) out.put(m.group(1).strip(), unfence(m.group(2)));
        return out;
    }

    /** A model sometimes wraps the file in a code fence inside the block. */
    private static String unfence(String text) {
        String t = text.stripTrailing();
        Matcher f = Pattern.compile("(?s)\\A\\s*```[a-zA-Z]*\\R(.*?)\\R```\\s*\\z").matcher(t);
        return (f.matches() ? f.group(1) : text.endsWith("\n") ? text : text + "\n");
    }

    /** The files as the model is shown them. */
    public static String render(Map<String, String> files) {
        StringBuilder sb = new StringBuilder();
        files.forEach(
                (p, c) ->
                        sb.append("=== file: ")
                                .append(p)
                                .append(" ===\n")
                                .append(c.endsWith("\n") ? c : c + "\n")
                                .append("=== end ===\n"));
        return sb.toString();
    }
}
