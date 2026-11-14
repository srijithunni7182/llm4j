package io.github.llm4j.tools.browser;

import io.github.llm4j.tools.ToolRefusal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A browser that plays scripted pages (D16 rule 7): each page has a title, a status, a text, the
 * requests it would make, what it writes to the console, the dialogs and downloads it would start,
 * and the elements it has. Every request goes through the same {@link RequestGate} as the real
 * browser, so a test sees exactly what a page would be refused.
 */
public final class FakeBrowser implements BrowserBackend {

    /** One scripted page. */
    public record FakePage(
            String title, int status, String text, List<String> requests, List<String> console, List<String> dialogs,
            List<String> downloads, Set<String> elements) {
        /** Copies the lists. */
        public FakePage {
            requests = List.copyOf(requests);
            console = List.copyOf(console);
            dialogs = List.copyOf(dialogs);
            downloads = List.copyOf(downloads);
            elements = Set.copyOf(elements);
        }

        /** A page with a title and text and nothing else. */
        public static FakePage of(String title, String text) {
            return new FakePage(title, 200, text, List.of(), List.of(), List.of(), List.of(), Set.of());
        }
    }

    private final RequestGate gate;
    private final Map<String, FakePage> pages;
    private final int maxRequests;
    private final List<String> console = Collections.synchronizedList(new ArrayList<>());
    private final List<Failed> failed = Collections.synchronizedList(new ArrayList<>());
    private final List<String> refused = Collections.synchronizedList(new ArrayList<>());
    /** What the test can check: what was clicked, filled and opened. */
    public final List<String> actions = Collections.synchronizedList(new ArrayList<>());
    private FakePage current;
    private boolean closed;

    public FakeBrowser(RequestGate gate, Map<String, FakePage> pages, int maxRequests) {
        this.gate = gate;
        this.pages = new LinkedHashMap<>(pages);
        this.maxRequests = maxRequests;
    }

    /** Whether {@link #close} was called. */
    public boolean closed() {
        return closed;
    }

    @Override
    public Page open(String url, Duration timeout) {
        FakePage page = pages.get(url);
        if (page == null) throw new ToolRefusal("could not load " + url + ": no such page");
        actions.add("open " + url);
        current = page;
        int made = 0;
        for (String r : page.requests()) {
            String why = gate.refusal(r);
            if (why == null && ++made > maxRequests) why = "more than max_requests (" + maxRequests + ") requests";
            if (why != null) failed.add(new Failed(r, why));
        }
        console.addAll(page.console());
        for (String d : page.dialogs()) refused.add("dialog dismissed: " + d);
        for (String d : page.downloads()) refused.add("download refused: " + d);
        return new Page(url, page.title(), page.status());
    }

    private FakePage page() {
        if (current == null) throw new ToolRefusal("no page is open: open one first");
        return current;
    }

    @Override
    public String text(int max) {
        String t = page().text();
        return t.length() <= max ? t : t.substring(0, max);
    }

    @Override
    public byte[] screenshot() {
        page();
        return new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};
    }

    @Override
    public boolean click(Target target) {
        actions.add("click " + target);
        return has(target);
    }

    @Override
    public boolean fill(Target target, String value) {
        actions.add("fill " + target + " = " + value);
        return has(target);
    }

    @Override
    public boolean waitFor(Target target, Duration timeout) {
        return has(target) || (target.text() != null && page().text().contains(target.text()));
    }

    private boolean has(Target target) {
        return page().elements().contains(target.toString());
    }

    @Override
    public List<String> console() {
        return List.copyOf(console);
    }

    @Override
    public List<Failed> failedRequests() {
        return List.copyOf(failed);
    }

    @Override
    public List<String> refused() {
        return List.copyOf(refused);
    }

    @Override
    public void close() {
        closed = true;
    }
}
