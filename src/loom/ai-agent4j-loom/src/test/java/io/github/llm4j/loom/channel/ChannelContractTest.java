package io.github.llm4j.loom.channel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.io.TempDir;

/** What every channel promises, run against each of them (spec loom-remote-answers R6). */
class ChannelContractTest {

    @TempDir
    Path dir;

    private final java.util.List<AutoCloseable> toClose = new java.util.ArrayList<>();

    /** A channel with a way for a test to make a reply arrive and a request fail. */
    interface Rig extends AutoCloseable {
        Channel channel();

        boolean receivesReplies();

        void inject(String text);

        /** Makes the next send fail; false when the channel cannot fail (the console). */
        boolean failNextSend();

        @Override
        default void close() { }
    }

    static Stream<String> rigs() {
        return Stream.of("telegram", "command", "console");
    }

    Rig rig(String which) throws Exception {
        Rig r = switch (which) {
            case "telegram" -> new Rig() {
                final FakeTelegram server = new FakeTelegram();
                final TelegramChannel channel = new TelegramChannel(server.base(), FakeTelegram.TOKEN, dir.resolve("offset.json"));

                public Channel channel() { return channel; }
                public boolean receivesReplies() { return true; }
                public void inject(String text) { server.reply(ChannelHarness.ME, text); }
                public boolean failNextSend() { server.failSends = 1; return true; }
                public void close() { server.close(); }
            };
            case "command" -> {
                Path log = dir.resolve("bridge.log");
                Path fail = dir.resolve("bridge.fail");
                CommandChannel channel = new CommandChannel(List.of("sh", "-c", "cat >> '" + log + "'; test ! -e '" + fail + "'"));
                yield new Rig() {
                    public Channel channel() { return channel; }
                    public boolean receivesReplies() { return false; }
                    public void inject(String text) { }
                    public boolean failNextSend() {
                        try {
                            Files.writeString(fail, "x");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                        return true;
                    }
                };
            }
            default -> {
                StringBuilder input = new StringBuilder();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                yield new Rig() {
                    final Object lock = new Object();
                    BufferedReader reader = new BufferedReader(new StringReader(""));
                    final ConsoleChannel channel = new ConsoleChannel(new PrintStream(out, true), new BufferedReader(new StringReader("")) {
                        public boolean ready() { return input.length() > 0; }
                        public String readLine() {
                            int nl = input.indexOf("\n");
                            String line = input.substring(0, nl);
                            input.delete(0, nl + 1);
                            return line;
                        }
                    });

                    public Channel channel() { return channel; }
                    public boolean receivesReplies() { return true; }
                    public void inject(String text) { input.append(text).append('\n'); }
                    public boolean failNextSend() { return false; }
                };
            }
        };
        toClose.add(r);
        return r;
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable c : toClose) c.close();
    }

    @ParameterizedTest
    @MethodSource("rigs")
    @Tag("RA-V6.1")
    void aQuestionIsSentOnceAndComesBackAsAWhereaboutsWithAReference(String which) throws Exception {
        Rig rig = rig(which);

        Channel.Sent sent = rig.channel().send(String.valueOf(ChannelHarness.ME), new Channel.Outgoing("ABC234", "Publish?\n\nReply: ABC234 yes | no", List.of("yes", "no"), null, false));

        assertThat(sent.ref()).isNotBlank();
        assertThat(rig.channel().name()).isEqualTo(which);
    }

    @ParameterizedTest
    @MethodSource("rigs")
    @Tag("RA-V6.1")
    void aReplyIsReadOnceAndForgottenAfterItIsAcknowledged(String which) throws Exception {
        Rig rig = rig(which);
        if (!rig.receivesReplies()) {
            assertThat(rig.channel().poll(Duration.ZERO).replies()).as("a bridge gets its replies as `weave answer` calls").isEmpty();
            return;
        }
        rig.inject("ABC234 yes");

        Channel.Batch first = rig.channel().poll(Duration.ZERO);
        assertThat(first.replies()).hasSize(1);
        assertThat(first.replies().get(0).text()).isEqualTo("ABC234 yes");
        rig.channel().acknowledge(first.cursor());

        assertThat(rig.channel().poll(Duration.ZERO).replies()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("rigs")
    @Tag("RA-V6.1")
    void aNoteCanBeSentBackAndAFailingSendIsAnIoExceptionNotAnythingElse(String which) throws Exception {
        Rig rig = rig(which);

        rig.channel().tell(String.valueOf(ChannelHarness.ME), "Recorded: yes for ABC234.");
        if (rig.failNextSend()) {
            assertThatThrownBy(() -> rig.channel().send(String.valueOf(ChannelHarness.ME), new Channel.Outgoing("ABC234", "x", List.of(), null, false))).isInstanceOf(IOException.class);
        }
    }

    @org.junit.jupiter.api.Test
    @Tag("RA-V6.2")
    void aCommandChannelPipesTheQuestionToAProgramAndAnswerFromThatProgramResumesTheRun() throws Exception {
        Path log = dir.resolve("bridge.log");
        CommandChannel channel = new CommandChannel(List.of("sh", "-c", "cat >> '" + log + "'"));

        Channel.Sent sent = channel.send("0", new Channel.Outgoing("QWE456", "Publish?", List.of("yes", "no"), "lead", true));

        assertThat(sent.ref()).isEqualTo("cmd:QWE456");
        String line = Files.readString(log);
        assertThat(line).contains("\"type\":\"question\"").contains("\"code\":\"QWE456\"").contains("\"text\":\"Publish?\"").contains("\"choices\":[\"yes\",\"no\"]").contains("\"to\":\"lead\"");
    }
}
