package io.github.llm4j.tools.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.tools.EgressProxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 35, D16, G65 and R15 with a real Chrome: a page served on one loopback port (the one-port
 * grant) that tries another loopback port and an unlisted host gets both refused and listed; its
 * dialog is dismissed; clicking and filling work; the browser stops on close. Needs a Chrome or
 * Chromium: {@code -Dbrowser.path=...}, the {@code PATH}, or Playwright's at /opt/pw-browsers.
 */
@Tag("integration")
class CdpBrowserTest {

    @TempDir Path dir;
    HttpServer page;
    HttpServer other;
    final List<String> otherHits = new CopyOnWriteArrayList<>();
    CdpBrowser browser;

    static Path chrome() {
        String given = System.getProperty("browser.path");
        if (given != null) return Path.of(given);
        Path found = CdpBrowser.onPath();
        if (found != null) return found;
        Path playwright = Path.of("/opt/pw-browsers/chromium");
        return Files.isExecutable(playwright) ? playwright : null;
    }

    @BeforeEach
    void serve() throws Exception {
        other = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other.createContext("/", x -> {
            otherHits.add(x.getRequestURI().toString());
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        other.start();
        page = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        String html = "<html><head><title>Shop</title></head><body><h1>Order form</h1>"
                + "<img src='http://127.0.0.1:" + other.getAddress().getPort() + "/pixel.png'>"
                + "<img src='https://blocked.example/track.png'>"
                + "<label for='q'>Quantity</label><input id='q' value='1'>"
                + "<button onclick=\"document.getElementById('out').textContent='ordered ' + document.getElementById('q').value\">Place order</button>"
                + "<p id='out'></p>"
                + "<script>console.log('hello from the page'); alert('free money');</script>"
                + "</body></html>";
        page.createContext("/", x -> {
            byte[] b = html.getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
            x.sendResponseHeaders(200, b.length);
            x.getResponseBody().write(b);
            x.close();
        });
        page.start();
    }

    @AfterEach
    void stop() {
        if (browser != null) browser.close();
        page.stop(0);
        other.stop(0);
    }

    @Test
    void everyRequestIsCheckedAndThePageCanBeUsed() throws Exception {
        Path bin = chrome();
        assumeTrue(bin != null, "no Chrome or Chromium here");
        int port = page.getAddress().getPort();
        RequestGate gate = new RequestGate(EgressProxy.Hosts.parse("allowed.example"), h -> List.of(InetAddress.getByName("93.184.215.14")), p -> p == port);
        Path profile = dir.resolve("profile");
        boolean root = "root".equals(System.getProperty("user.name"));
        browser = CdpBrowser.start(new CdpBrowser.Launch(bin, profile, !root, Duration.ofSeconds(30)), gate, 50);

        BrowserBackend.Page p = browser.open("http://127.0.0.1:" + port + "/", Duration.ofSeconds(20));
        assertThat(p.title()).isEqualTo("Shop");
        assertThat(p.status()).isEqualTo(200);
        assertThat(browser.text(10_000)).contains("Order form");

        assertThat(browser.failedRequests()).extracting(BrowserBackend.Failed::url)
                .anyMatch(u -> u.contains(":" + other.getAddress().getPort() + "/pixel.png"))
                .anyMatch(u -> u.contains("blocked.example"));
        assertThat(otherHits).as("the other loopback port was never reached").isEmpty();
        assertThat(browser.refused()).anyMatch(r -> r.contains("dialog dismissed") && r.contains("free money"));
        assertThat(browser.console()).anyMatch(c -> c.contains("hello from the page"));

        assertThat(browser.fill(new BrowserBackend.Target(null, "Quantity"), "3")).isTrue();
        assertThat(browser.click(new BrowserBackend.Target(null, "Place order"))).isTrue();
        assertThat(browser.waitFor(new BrowserBackend.Target(null, "ordered 3"), Duration.ofSeconds(5))).isTrue();
        assertThat(browser.click(new BrowserBackend.Target("#missing", null))).isFalse();
        byte[] png = browser.screenshot();
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');

        long pid = Long.parseLong(Files.readString(profile.resolve(Profiles.PID)).strip());
        browser.close();
        browser = null;
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).as("the browser stopped").isFalse();
        assertThat(profile).doesNotExist();
    }

    @Test
    void aBrowserACrashedRunLeftIsStoppedBeforeANewOneStarts() throws Exception {
        Path bin = chrome();
        assumeTrue(bin != null, "no Chrome or Chromium here");
        int port = page.getAddress().getPort();
        RequestGate gate = new RequestGate(null, h -> List.of(), p -> p == port);
        Path profile = dir.resolve("left");
        boolean root = "root".equals(System.getProperty("user.name"));
        CdpBrowser left = CdpBrowser.start(new CdpBrowser.Launch(bin, profile, !root, Duration.ofSeconds(30)), gate, 50);
        long pid = Long.parseLong(Files.readString(profile.resolve(Profiles.PID)).strip());
        // the run "crashes": nothing closes it
        assertThat(Profiles.clearLeftover(profile)).isTrue();
        Thread.sleep(500);
        assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        assertThat(profile).doesNotExist();
        left.close();
    }
}
