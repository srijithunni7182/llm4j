package io.github.llm4j.eval.support;

/** Canned judge/generator responses in the fenced-JSON format eval4j expects. */
public final class JudgeResponses {

    private JudgeResponses() {}

    public static String rating(int rating, String reasoning) {
        return "```json\n{\"reasoning\": \"" + reasoning + "\", \"rating\": " + rating + "}\n```";
    }

    public static String statements(String... statements) {
        StringBuilder sb = new StringBuilder("```json\n{\"statements\": [");
        for (int i = 0; i < statements.length; i++) {
            sb.append(i > 0 ? ", " : "").append('"').append(statements[i]).append('"');
        }
        return sb.append("]}\n```").toString();
    }

    public static String json(String body) {
        return "```json\n" + body + "\n```";
    }
}
