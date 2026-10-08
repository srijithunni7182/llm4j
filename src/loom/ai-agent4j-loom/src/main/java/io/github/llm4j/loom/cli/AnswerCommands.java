package io.github.llm4j.loom.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.llm4j.loom.channel.Answers;
import io.github.llm4j.loom.channel.Channels;
import io.github.llm4j.loom.channel.Pending;
import io.github.llm4j.loom.channel.PendingStore;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** {@code weave answer} and {@code weave questions}: answering a question that was sent through a channel, from a terminal on the host. */
final class AnswerCommands {

    private static final ObjectMapper JSON = new ObjectMapper();

    private AnswerCommands() { }

    @Command(name = "answer", description = "Answers a question that is waiting for a person, and leaves a trigger so the run carries on at the next tick.")
    static class Answer implements Callable<Integer> {
        @Parameters(index = "0", description = "The run store.")
        private File store;

        @Parameters(index = "1", description = "The question's code (weave questions lists them).")
        private String code;

        @Parameters(index = "2..*", arity = "1..*", description = "The answer.")
        private List<String> text;

        @Option(names = "--by", description = "Who is answering (default: the operating system user).")
        private String by;

        @Override
        public Integer call() {
            return answer(store.toPath(), code, String.join(" ", text), by != null ? by : "operator:" + System.getProperty("user.name", "unknown"), WeaveEnv.system());
        }
    }

    static int answer(Path store, String code, String text, String by, WeaveEnv env) {
        Answers.Outcome o = Channels.answersFor(store, env.clock()).record(code, text, by);
        (o.recorded() ? env.out() : env.err()).println((o.recorded() ? "" : "Error: ") + o.message()
                + (o.recorded() ? " The run carries on at the next `weave tick` (or daemon)." : ""));
        return o.recorded() ? 0 : 1;
    }

    @Command(name = "questions", description = "Lists the questions waiting for a person (--all: answered and expired ones too).")
    static class Questions implements Callable<Integer> {
        @Parameters(index = "0", description = "The run store.")
        private File store;

        @Option(names = "--all", description = "Include answered and expired questions.")
        private boolean all;

        @Option(names = "--json", description = "Print JSON.")
        private boolean json;

        @Override
        public Integer call() throws Exception {
            return questions(store.toPath(), all, json, WeaveEnv.system());
        }
    }

    static int questions(Path store, boolean all, boolean json, WeaveEnv env) throws Exception {
        List<Pending> list = new ArrayList<>(new PendingStore(store).all());
        if (!all) list.removeIf(p -> !p.open());
        if (json) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Pending p : list) out.add(p.toMap());
            env.out().println(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));
            return 0;
        }
        if (list.isEmpty()) {
            env.out().println(all ? "No questions have been asked from this store." : "Nothing is waiting for an answer.");
            return 0;
        }
        Instant now = env.clock().instant();
        for (Pending p : list) {
            Duration age = Duration.between(p.createdAt(), now);
            String where = p.sent().isEmpty() && p.open() ? "  NOT SENT YET" : "";
            String first = p.question().lines().findFirst().orElse("");
            env.out().println(p.code() + "  " + p.state().name().toLowerCase() + "  " + Path.of(p.run()).getFileName() + "  " + io.github.llm4j.loom.execution.HarnessExecutor.human(age.isNegative() ? Duration.ZERO : age)
                    + "  " + (first.length() > 100 ? first.substring(0, 100) + "…" : first) + where);
            if (p.answer() != null) env.out().println("      answered \"" + p.answer().text() + "\" by " + p.answer().by());
        }
        return 0;
    }

}
