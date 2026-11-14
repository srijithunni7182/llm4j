package io.github.llm4j.tools.browser;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.task.TaskEffect;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.agent.tool.Isolation;
import io.github.llm4j.agent.tool.ToolProfile;
import io.github.llm4j.tools.EgressProxy;
import io.github.llm4j.tools.GenericKind;
import io.github.llm4j.tools.NetPolicy;
import io.github.llm4j.tools.OptionException;
import io.github.llm4j.tools.Options;
import io.github.llm4j.tools.SafePaths;
import io.github.llm4j.tools.SandboxKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * {@code tool Web { use: browser  hosts: "example.com" }}: a headless Chrome the user has installed,
 * driven over the DevTools protocol (phase 35, D16). Every request a page makes is checked; page
 * content is untrusted; a screenshot is a file, not an image for a model.
 */
public final class BrowserKind extends GenericKind {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(2);
    static final int DEFAULT_CHARS = 50_000;
    static final int MAX_CHARS = 500_000;
    static final int DEFAULT_REQUESTS = 200;
    static final int MAX_REQUESTS = 2_000;
    static final long DEFAULT_SCREENSHOT = 5L * 1024 * 1024;
    static final long MAX_SCREENSHOT = 20L * 1024 * 1024;
    static final Duration START = Duration.ofSeconds(30);

    /** Makes the backend for a declaration: the real browser, or a fake in tests. */
    @FunctionalInterface
    public interface Backends {
        BrowserBackend start(Config config, RequestGate gate) throws java.io.IOException;
    }

    private final Backends backends;

    /** The real browser. */
    public BrowserKind() {
        this((c, gate) -> CdpBrowser.start(new CdpBrowser.Launch(c.binary(), c.profile(), c.chromeSandbox(), START), gate, c.maxRequests()));
    }

    /** Backends made by {@code backends}: a seam for tests (a {@link FakeBrowser}). */
    public BrowserKind(Backends backends) {
        this.backends = backends;
    }

    @Override
    public String name() {
        return "browser";
    }

    @Override
    public Set<String> optional() {
        return Set.of("hosts", "reaches", "browser", "chrome_sandbox", "screenshots", "timeout", "max_chars", "max_requests", "max_screenshot");
    }

    @Override
    public Set<String> prefixes() {
        return Set.of("reaches.");
    }

    /** {@code reaches: Py} names a sandbox whose served program the browser may load (D7). */
    @Override
    public Map<String, String> references() {
        return Map.of("reaches", "sandbox");
    }

    /** Built as the run starts, so a crashed run's browser is stopped first. */
    @Override
    public boolean buildsAtRunStart() {
        return true;
    }

    /** Everything a page shows was written by whoever made the page. */
    @Override
    public Set<String> uncheckedResult(Map<String, String> options) {
        return Set.of("title", "url", "text", "console", "failed_requests", "refused");
    }

    @Override
    public ToolProfile profile(Map<String, String> options) {
        String hosts = options.get("hosts");
        String reaches = options.get("reaches");
        String note = "a browser (fresh profile, every request checked) reaching "
                + (hosts != null ? hosts : "")
                + (hosts != null && reaches != null ? " and " : "")
                + (reaches != null ? "the program " + reaches + " serves" : "")
                + "; a click or a filled form can send what the page asks";
        return new ToolProfile(true, false, true, TaskEffect.CHANGES, Isolation.NONE, true, true, note);
    }

    /** The settings of one declaration, checked. */
    public record Config(
            EgressProxy.Hosts hosts, boolean reaches, Path binary, boolean chromeSandbox, Path screenshots, Path profile,
            Duration timeout, int maxChars, int maxRequests, long maxScreenshot) {}

    Config config(Options o, Path baseDir, String toolName, boolean findBrowser) {
        String hostList = o.get("hosts");
        if (hostList == null && !o.has("reaches"))
            throw new OptionException("hosts: the hosts pages may load from, e.g. hosts: \"example.com, *.example.com\" (or reaches: a sandbox that serves)");
        String problem = hostList == null ? null : EgressProxy.Hosts.problem(hostList);
        if (problem != null) throw new OptionException(problem);
        Path screenshots = null;
        if (o.has("screenshots")) {
            try {
                screenshots = SafePaths.inside(baseDir, o.get("screenshots"));
            } catch (IllegalArgumentException e) {
                throw new OptionException("screenshots: " + e.getMessage());
            }
        }
        Path binary = o.has("browser") ? Path.of(o.get("browser")) : findBrowser ? CdpBrowser.onPath() : null;
        if (findBrowser && (binary == null || !Files.isExecutable(binary)))
            throw new OptionException("browser: no Chrome or Chromium was found ("
                    + (o.has("browser") ? o.get("browser") + " is not a program" : "looked for " + String.join(", ", CdpBrowser.NAMES) + " on the PATH")
                    + "); install one or name it with browser: \"/path/to/chrome\"");
        return new Config(
                hostList == null ? null : EgressProxy.Hosts.parse(hostList),
                o.has("reaches"),
                binary,
                o.bool("chrome_sandbox", true),
                screenshots,
                baseDir.toAbsolutePath().normalize().resolve(".loom-browser").resolve(toolName),
                o.duration("timeout", DEFAULT_TIMEOUT, MAX_TIMEOUT),
                o.integer("max_chars", DEFAULT_CHARS, 1, MAX_CHARS),
                o.integer("max_requests", DEFAULT_REQUESTS, 1, MAX_REQUESTS),
                o.size("max_screenshot", DEFAULT_SCREENSHOT, MAX_SCREENSHOT));
    }

    @Override
    protected void validate(Options o, Path baseDir) {
        config(o, baseDir, "browser", false);
    }

    @Override
    protected Tool build(String name, Options o, Path baseDir, EffectContext context) {
        Config c = config(o, baseDir, name, true);
        IntPredicate served = c.reaches() ? SandboxKind.servedPorts(o.values(), baseDir, context.clock()) : null;
        RequestGate gate = new RequestGate(c.hosts(), NetPolicy.Resolver.SYSTEM, served);
        if (Profiles.clearLeftover(c.profile())) {
            Map<String, Object> data = new java.util.LinkedHashMap<>();
            data.put("tool", name);
            context.audit("browser_leftover_stopped", data);
        }
        return new BrowserTool(name, c, () -> backends.start(c, gate), gate, context);
    }
}
