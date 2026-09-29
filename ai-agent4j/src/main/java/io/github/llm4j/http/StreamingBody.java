package io.github.llm4j.http;

import io.github.llm4j.exception.LLMException;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * The open body of a successful streaming response (see {@link HttpClientWrapper#stream}). Read it
 * once, as {@link #events()} or {@link #lines()}: lazily, as the provider sends it. The connection is
 * released when the stream is exhausted or closed.
 */
public final class StreamingBody implements Closeable {

    /** One server-sent event: its {@code event:} name (null if none) and its {@code data:} (lines joined). */
    public record SseEvent(String name, String data) {}

    private final Response response;
    private final BufferedReader reader;
    private boolean opened;

    StreamingBody(Response response) {
        this.response = response;
        ResponseBody body = response.body();
        this.reader = new BufferedReader(new InputStreamReader(
                body == null ? java.io.InputStream.nullInputStream() : body.byteStream(), StandardCharsets.UTF_8));
    }

    /** Newline-delimited records (e.g. Ollama's NDJSON); blank lines skipped. */
    public Stream<String> lines() {
        claim();
        return lazy(new Iterator<>() {
            private String next;
            private boolean done;

            @Override
            public boolean hasNext() {
                while (next == null) {
                    if (done) return false;
                    String line = readLine();
                    if (line == null) {
                        done = true;
                        close();
                        return false;
                    }
                    if (!line.isBlank()) next = line;
                }
                return true;
            }

            @Override
            public String next() {
                if (!hasNext()) throw new NoSuchElementException();
                String out = next;
                next = null;
                return out;
            }
        });
    }

    /**
     * Server-sent events: {@code event:} and {@code data:} fields up to a blank line; comments ({@code
     * :…}) and keep-alives are skipped; several {@code data:} lines are joined with a newline.
     */
    public Stream<SseEvent> events() {
        claim();
        return lazy(new Iterator<>() {
            private SseEvent next;
            private boolean done;

            @Override
            public boolean hasNext() {
                if (next != null) return true;
                if (done) return false;
                String name = null;
                StringBuilder data = null;
                while (true) {
                    String line = readLine();
                    if (line == null || line.isEmpty()) {
                        if (data != null) {
                            next = new SseEvent(name, data.toString());
                            if (line == null) done = true;
                            return true;
                        }
                        if (line == null) {
                            done = true;
                            close();
                            return false;
                        }
                        name = null; // a blank line with no data: nothing to dispatch
                        continue;
                    }
                    if (line.startsWith(":")) continue;
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    if (field.equals("event")) {
                        name = value;
                    } else if (field.equals("data")) {
                        if (data == null) data = new StringBuilder(value);
                        else data.append('\n').append(value);
                    }
                }
            }

            @Override
            public SseEvent next() {
                if (!hasNext()) throw new NoSuchElementException();
                SseEvent out = next;
                next = null;
                return out;
            }
        });
    }

    private void claim() {
        if (opened) throw new IllegalStateException("a streaming body can be read only once");
        opened = true;
    }

    private String readLine() {
        try {
            return reader.readLine();
        } catch (IOException e) {
            close();
            throw new LLMException("Stream interrupted: " + e.getMessage(), e);
        }
    }

    private <T> Stream<T> lazy(Iterator<T> it) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(it, 0), false).onClose(this::close);
    }

    @Override
    public void close() {
        try {
            reader.close();
        } catch (IOException ignored) {
            // closing anyway
        } catch (UncheckedIOException ignored) {
            // closing anyway
        }
        response.close();
    }
}
