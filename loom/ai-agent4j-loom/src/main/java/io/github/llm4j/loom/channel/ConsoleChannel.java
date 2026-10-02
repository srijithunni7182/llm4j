package io.github.llm4j.loom.channel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The terminal as a channel: questions are printed, replies are lines of {@code <code> <answer>} read from the input. */
public final class ConsoleChannel implements Channel {

    private final PrintStream out;
    private final BufferedReader in;

    public ConsoleChannel(PrintStream out, BufferedReader in) {
        this.out = out;
        this.in = in;
    }

    @Override
    public String name() {
        return "console";
    }

    @Override
    public Sent send(String chat, Outgoing question) {
        out.println(question.text());
        return new Sent("console", question.code(), Instant.now());
    }

    @Override
    public Batch poll(Duration wait) throws IOException {
        List<Reply> replies = new ArrayList<>();
        while (in.ready()) {
            String line = in.readLine();
            if (line == null) break;
            replies.add(new Reply("console", "console", line, null));
        }
        return new Batch(replies, "console");
    }

    @Override
    public void acknowledge(String cursor) { }

    @Override
    public void tell(String chat, String text) {
        out.println(text);
    }
}
