package io.github.llm4j.tools.browser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.llm4j.tools.EgressProxy;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Starting the browser without one: a stand-in program announces a DevTools socket the test serves,
 * so the launch, the connection and every guard are exercised in the default build.
 */
class CdpStartTest {

    @TempDir Path dir;

    Path program(String body) throws Exception {
        Path p = dir.resolve("browser.sh");
        Files.writeString(p, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwx------"));
        return p;
    }

    @Test
    void itStartsConnectsAndGuardsTheTab() throws Exception {
        try (StandInDevTools devtools = new StandInDevTools()) {
            Path bin = program("echo \"DevTools listening on " + devtools.url() + "\" 1>&2\nexec sleep 30");
            RequestGate gate = new RequestGate(EgressProxy.Hosts.parse("example.com"), h -> List.of(InetAddress.getByName("93.184.215.14")), null);
            Path profile = dir.resolve("profile");
            CdpBrowser b = CdpBrowser.start(new CdpBrowser.Launch(bin, profile, false, Duration.ofSeconds(10)), gate, 10);
            assertThat(Files.readString(profile.resolve(Profiles.PID))).isNotBlank();
            assertThat(devtools.methods).containsSubsequence(
                    "Browser.setDownloadBehavior", "Target.createTarget", "Target.attachToTarget", "Fetch.enable", "Network.enable");
            b.close();
            assertThat(profile).doesNotExist();
        }
    }

    @Test
    void aBrowserThatExitsOrSaysNothingIsAnErrorThatSaysSo() throws Exception {
        RequestGate gate = new RequestGate(null, h -> List.of(), p -> true);
        Path exits = program("exit 3");
        assertThatThrownBy(() -> CdpBrowser.start(new CdpBrowser.Launch(exits, dir.resolve("a"), true, Duration.ofSeconds(10)), gate, 10))
                .hasMessageContaining("did not start");
        Path silent = program("exec sleep 30");
        assertThatThrownBy(() -> CdpBrowser.start(new CdpBrowser.Launch(silent, dir.resolve("b"), true, Duration.ofMillis(300)), gate, 10))
                .hasMessageContaining("did not start within");
        Path wrongSocket = program("echo \"DevTools listening on ws://127.0.0.1:1/devtools/browser/x\" 1>&2\nexec sleep 30");
        assertThatThrownBy(() -> CdpBrowser.start(new CdpBrowser.Launch(wrongSocket, dir.resolve("c"), true, Duration.ofSeconds(10)), gate, 10))
                .hasMessageContaining("could not connect");
    }

    @Test
    void aLeftoverThatIsNotABrowserIsNeverStopped() throws Exception {
        Path profile = Files.createDirectories(dir.resolve("p"));
        Files.writeString(profile.resolve(Profiles.PID), String.valueOf(ProcessHandle.current().pid()));
        assertThat(Profiles.clearLeftover(profile)).isFalse();
        assertThat(ProcessHandle.current().isAlive()).isTrue();
        Path junk = Files.createDirectories(dir.resolve("q"));
        Files.writeString(junk.resolve(Profiles.PID), "not a number");
        assertThat(Profiles.clearLeftover(junk)).isFalse();
        assertThat(junk).doesNotExist();
        assertThat(Profiles.clearLeftover(dir.resolve("never-made"))).isFalse();
    }
}
