package io.github.llm4j.tools.browser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.llm4j.tools.EgressProxy;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.ToolRefusal;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The reference backend (D16): a Chrome or Chromium the user has installed, started headless with a
 * fresh profile, driven over the DevTools protocol. Every request a page makes is paused
 * ({@code Fetch.enable}) and let through only when the {@link RequestGate} allows it; https also goes
 * through an egress proxy that resolves a name once and refuses internal addresses, so a name that
 * answers differently the second time gains nothing. Downloads are denied, file pickers and dialogs
 * are refused and recorded, permission prompts are denied.
 */
public final class CdpBrowser implements BrowserBackend {

    /** The names a browser is looked for on the {@code PATH}, in this order. */
    static final List<String> NAMES = List.of("chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "chrome");
    private static final Pattern LISTENING = Pattern.compile("DevTools listening on (ws://\\S+)");
    private static final Duration COMMAND = Duration.ofSeconds(30);

    /** How the browser is started. */
    public record Launch(Path binary, Path profile, boolean chromeSandbox, Duration startTimeout) {}

    private final RequestGate gate;
    private final int maxRequests;
    private final Process process;
    private final EgressProxy proxy;
    private final Channel cdp;
    private final String session;
    private final Path profile;
    private final List<String> console = Collections.synchronizedList(new ArrayList<>());
    private final List<Failed> failed = Collections.synchronizedList(new ArrayList<>());
    private final List<String> refused = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> urls = new ConcurrentHashMap<>();
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();
    private final AtomicInteger requests = new AtomicInteger();
    private volatile CountDownLatch loaded = new CountDownLatch(1);
    private volatile int status;
    private volatile boolean closed;

    CdpBrowser(RequestGate gate, int maxRequests, Process process, EgressProxy proxy, Channel cdp, String session, Path profile) {
        this.gate = gate;
        this.maxRequests = maxRequests;
        this.process = process;
        this.proxy = proxy;
        this.cdp = cdp;
        this.session = session;
        this.profile = profile;
    }

    /** The first browser on the {@code PATH}, or null. */
    static Path onPath() {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String name : NAMES)
            for (String dir : path.split(java.io.File.pathSeparator)) {
                if (dir.isBlank()) continue;
                Path p = Path.of(dir, name);
                if (Files.isExecutable(p)) return p;
            }
        return null;
    }

    /** Starts the browser, connects to it and opens one tab with every guard in place. */
    public static CdpBrowser start(Launch launch, RequestGate gate, int maxRequests) throws IOException {
        Files.createDirectories(launch.profile());
        EgressProxy proxy =
                gate.hosts() == null
                        ? null
                        : EgressProxy.start(InetAddress.getLoopbackAddress(), 0, gate.hosts(), NetPolicy.Resolver.SYSTEM, EgressProxy.Connector.DIRECT);
        List<String> command = new ArrayList<>(List.of(
                launch.binary().toString(),
                "--headless=new",
                "--remote-debugging-port=0",
                "--remote-allow-origins=http://127.0.0.1",
                "--user-data-dir=" + launch.profile().toAbsolutePath(),
                "--no-first-run",
                "--no-default-browser-check",
                "--disable-extensions",
                "--disable-sync",
                "--disable-background-networking",
                "--disable-component-update",
                "--disable-default-apps",
                "--disable-domain-reliability",
                "--deny-permission-prompts",
                "--mute-audio",
                "--disable-features=DnsOverHttps,InterestFeedContentSuggestions"));
        if (proxy != null) {
            command.add("--proxy-server=http://127.0.0.1:" + proxy.port());
            // without a served program, loopback goes to the proxy too, which refuses it
            if (!gate.reachesServed()) command.add("--proxy-bypass-list=<-loopback>");
        }
        if (!launch.chromeSandbox()) command.add("--no-sandbox");
        command.add("about:blank");
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            Files.writeString(launch.profile().resolve("llm4j.pid"), String.valueOf(process.pid()));
            URI browserSocket = listening(process, launch.startTimeout());
            Holder holder = new Holder();
            Cdp cdp = Cdp.connect(browserSocket, COMMAND, (method, params) -> {
                CdpBrowser b = holder.browser;
                if (b != null) b.event(method, params);
            });
            ObjectNode deny = Cdp.params().put("behavior", "deny").put("eventsEnabled", true);
            cdp.call("Browser.setDownloadBehavior", deny, null, COMMAND);
            String target = cdp.call("Target.createTarget", Cdp.params().put("url", "about:blank"), null, COMMAND).path("targetId").asText();
            String session = cdp.call("Target.attachToTarget", Cdp.params().put("targetId", target).put("flatten", true), null, COMMAND)
                    .path("sessionId").asText();
            CdpBrowser browser = new CdpBrowser(gate, maxRequests, process, proxy, cdp, session, launch.profile());
            holder.browser = browser;
            browser.guard();
            return browser;
        } catch (IOException | RuntimeException e) {
            process.destroyForcibly();
            closeProxy(proxy);
            throw e;
        }
    }

    private static final class Holder {
        volatile CdpBrowser browser;
    }

    /** Reads the browser's error stream until it says where its DevTools socket is. */
    private static URI listening(Process process, Duration timeout) throws IOException {
        BufferedReader err = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
        URI[] found = {null};
        CountDownLatch ready = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                for (String line = err.readLine(); line != null; line = err.readLine()) {
                    Matcher m = LISTENING.matcher(line);
                    if (found[0] == null && m.find()) {
                        found[0] = URI.create(m.group(1));
                        ready.countDown();
                    }
                }
            } catch (IOException ended) {
                // the browser has stopped
            }
            ready.countDown();
        }, "llm4j-browser-stderr");
        reader.setDaemon(true);
        reader.start();
        try {
            if (!ready.await(timeout.toMillis(), TimeUnit.MILLISECONDS) || found[0] == null)
                throw new IOException("the browser did not start within " + timeout.toSeconds() + "s"
                        + (process.isAlive() ? "" : " (it exited with " + process.exitValue() + ")"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while starting the browser", e);
        }
        return found[0];
    }

    /** Turns on the guards for the tab: every request paused, dialogs and pickers caught. */
    void guard() throws IOException {
        ObjectNode pattern = Cdp.params().put("urlPattern", "*").put("requestStage", "Request");
        ObjectNode fetch = Cdp.params();
        fetch.putArray("patterns").add(pattern);
        fetch.put("handleAuthRequests", false);
        cdp.call("Fetch.enable", fetch, session, COMMAND);
        cdp.call("Network.enable", null, session, COMMAND);
        cdp.call("Page.enable", null, session, COMMAND);
        cdp.call("Runtime.enable", null, session, COMMAND);
        cdp.call("Page.setInterceptFileChooserDialog", Cdp.params().put("enabled", true), session, COMMAND);
    }

    /** Every event of the browser, on the socket's thread: never block here. */
    void event(String method, JsonNode p) {
        switch (method) {
            case "Fetch.requestPaused" -> paused(p);
            case "Network.requestWillBeSent" -> urls.put(p.path("requestId").asText(), p.path("request").path("url").asText());
            case "Network.responseReceived" -> {
                if ("Document".equals(p.path("type").asText()) && status == 0) status = p.path("response").path("status").asInt();
            }
            case "Network.loadingFailed" -> {
                String url = urls.getOrDefault(p.path("requestId").asText(), "");
                if (!blocked.contains(url) && !"net::ERR_ABORTED".equals(p.path("errorText").asText()))
                    failed.add(new Failed(url, p.path("errorText").asText()));
            }
            case "Page.loadEventFired" -> loaded.countDown();
            case "Page.javascriptDialogOpening" -> {
                refused.add("dialog dismissed: " + p.path("type").asText() + " " + Limit.cut(p.path("message").asText(), 200));
                cdp.post("Page.handleJavaScriptDialog", Cdp.params().put("accept", false), session);
            }
            case "Page.fileChooserOpened" -> refused.add("file picker refused");
            case "Browser.downloadWillBegin" -> refused.add("download refused: " + Limit.cut(p.path("url").asText(), 300));
            case "Runtime.consoleAPICalled" -> {
                StringBuilder line = new StringBuilder(p.path("type").asText()).append(':');
                for (JsonNode a : p.path("args")) line.append(' ').append(a.has("value") ? a.get("value").asText() : a.path("description").asText());
                console.add(Limit.cut(line.toString(), 500));
            }
            case "Runtime.exceptionThrown" -> console.add(Limit.cut("error: " + p.path("exceptionDetails").path("text").asText(), 500));
            default -> {}
        }
    }

    private void paused(JsonNode p) {
        String id = p.path("requestId").asText();
        String url = p.path("request").path("url").asText();
        String why = gate.refusal(url);
        if (why == null && requests.incrementAndGet() > maxRequests) why = "more than max_requests (" + maxRequests + ") requests";
        if (why == null) {
            cdp.post("Fetch.continueRequest", Cdp.params().put("requestId", id), session);
            return;
        }
        blocked.add(url);
        failed.add(new Failed(Limit.cut(url, 300), why));
        cdp.post("Fetch.failRequest", Cdp.params().put("requestId", id).put("errorReason", "BlockedByClient"), session);
    }

    @Override
    public Page open(String url, Duration timeout) throws IOException {
        loaded = new CountDownLatch(1);
        status = 0;
        JsonNode nav = cdp.call("Page.navigate", Cdp.params().put("url", url), session, timeout);
        if (nav.hasNonNull("errorText") && !nav.get("errorText").asText().isEmpty())
            throw new ToolRefusal("could not load " + url + ": " + nav.get("errorText").asText());
        try {
            if (!loaded.await(timeout.toMillis(), TimeUnit.MILLISECONDS))
                throw new ToolRefusal("the page did not finish loading within " + timeout.toSeconds() + "s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while loading", e);
        }
        return new Page(evaluate("location.href").asText(), evaluate("document.title").asText(), status);
    }

    private JsonNode evaluate(String expression) throws IOException {
        ObjectNode p = Cdp.params().put("expression", expression).put("returnByValue", true).put("awaitPromise", false);
        JsonNode r = cdp.call("Runtime.evaluate", p, session, COMMAND);
        if (r.has("exceptionDetails")) throw new ToolRefusal("the page's script failed: " + r.path("exceptionDetails").path("text").asText());
        return r.path("result").path("value");
    }

    @Override
    public String text(int max) throws IOException {
        String t = evaluate("document.body ? document.body.innerText : ''").asText();
        return t.length() <= max ? t : t.substring(0, max);
    }

    @Override
    public byte[] screenshot() throws IOException {
        JsonNode r = cdp.call("Page.captureScreenshot", Cdp.params().put("format", "png"), session, COMMAND);
        return Base64.getDecoder().decode(r.path("data").asText());
    }

    /** A script that finds the element, with the target given as JSON literals, never as script. */
    private static String find(Target t) {
        return "(function(sel, txt){"
                + "if (sel) return document.querySelector(sel);"
                + "const want = txt.trim().toLowerCase();"
                + "for (const l of document.querySelectorAll('label')) if (l.textContent.trim().toLowerCase() === want && l.control) return l.control;"
                + "const all = Array.from(document.querySelectorAll('a,button,input,textarea,select,summary,option,[role=button],[onclick]'));"
                + "const name = e => (e.innerText || e.value || e.getAttribute('aria-label') || e.placeholder || '').trim().toLowerCase();"
                + "return all.find(e => name(e) === want) || all.find(e => name(e).includes(want)) || null;"
                + "})(" + Limit.json(t.selector()) + ", " + Limit.json(t.text()) + ")";
    }

    @Override
    public boolean click(Target target) throws IOException {
        return evaluate("(function(e){ if (!e) return false; e.scrollIntoView({block:'center'}); e.click(); return true; })(" + find(target) + ")").asBoolean();
    }

    @Override
    public boolean fill(Target target, String value) throws IOException {
        return evaluate("(function(e, v){ if (!e) return false; e.focus();"
                        + " const proto = Object.getPrototypeOf(e); const d = Object.getOwnPropertyDescriptor(proto, 'value');"
                        + " if (d && d.set) d.set.call(e, v); else e.value = v;"
                        + " e.dispatchEvent(new Event('input', {bubbles: true})); e.dispatchEvent(new Event('change', {bubbles: true})); return true; })("
                        + find(target) + ", " + Limit.json(value) + ")")
                .asBoolean();
    }

    @Override
    public boolean waitFor(Target target, Duration timeout) throws IOException {
        String test = target.text() != null
                ? "(" + find(target) + ") !== null || (document.body && document.body.innerText.includes(" + Limit.json(target.text()) + "))"
                : "(" + find(target) + ") !== null";
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (evaluate(test).asBoolean()) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while waiting", e);
            }
        }
        return false;
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
        if (closed) return;
        closed = true;
        try {
            cdp.call("Browser.close", null, null, Duration.ofSeconds(3));
        } catch (IOException | RuntimeException gone) {
            // it is stopped below either way
        }
        cdp.close();
        if (process != null) stopProcess(process);
        closeProxy(proxy);
        Profiles.delete(profile);
    }

    private static void stopProcess(Process process) {
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void closeProxy(EgressProxy proxy) {
        if (proxy == null) return;
        try {
            proxy.close();
        } catch (IOException e) {
            // its socket is closing either way; nothing more can be done for it
        }
    }
}
