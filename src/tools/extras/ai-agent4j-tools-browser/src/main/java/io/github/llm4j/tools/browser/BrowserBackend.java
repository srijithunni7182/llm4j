package io.github.llm4j.tools.browser;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A browser the {@code browser} tool drives (D16): {@link CdpBrowser} over an installed Chrome, or
 * {@link FakeBrowser} for tests. Every implementation asks its {@link RequestGate} about every
 * request a page makes, refuses downloads, file pickers and permission prompts, dismisses dialogs and
 * records each of these.
 */
public interface BrowserBackend extends AutoCloseable {

    /** What an element is found by: a CSS selector, or its visible text or label. */
    record Target(String selector, String text) {
        /** Exactly one of the two. */
        public Target {
            if ((selector == null) == (text == null)) throw new IllegalArgumentException("give a selector or a text, not both");
        }

        @Override
        public String toString() {
            return selector != null ? selector : "\"" + text + "\"";
        }
    }

    /** A page after it loaded. */
    record Page(String url, String title, int status) {
        /** Checks nothing is missing. */
        public Page {
            Objects.requireNonNull(url, "url");
            title = title == null ? "" : title;
        }
    }

    /** A request that did not load: refused by the gate or failed on the network. */
    record Failed(String url, String reason) {}

    /** Loads {@code url} (already allowed by the gate) and waits for it, at most {@code timeout}. */
    Page open(String url, Duration timeout) throws IOException;

    /** The visible text of the page, at most {@code max} characters (more is cut). */
    String text(int max) throws IOException;

    /** A PNG of the page as it is now. */
    byte[] screenshot() throws IOException;

    /** Clicks the element; false when there is none. */
    boolean click(Target target) throws IOException;

    /** Types {@code value} into the field; false when there is none. */
    boolean fill(Target target, String value) throws IOException;

    /** Waits until the element (or text) is there; false when the time ran out. */
    boolean waitFor(Target target, Duration timeout) throws IOException;

    /** What the page wrote to its console, oldest first. */
    List<String> console();

    /** Requests that did not load, oldest first. */
    List<Failed> failedRequests();

    /** Dialogs dismissed, downloads and file pickers refused, oldest first. */
    List<String> refused();

    /** Stops the browser; closing twice is not an error. */
    @Override
    void close();
}
