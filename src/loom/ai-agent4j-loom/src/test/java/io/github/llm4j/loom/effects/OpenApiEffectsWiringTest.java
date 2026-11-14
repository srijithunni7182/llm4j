package io.github.llm4j.loom.effects;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.tool.EffectContext;
import io.github.llm4j.loom.tools.ToolFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An {@code openapi} tool's {@code effects:} map reaches the run through the production path (phase
 * 36a, D13 and G77): the script's declaration goes through {@link ToolFactory}, the tool built from
 * it is called, and the effect journal shows which calls were effects. Nothing here calls a helper.
 */
class OpenApiEffectsWiringTest {

    @TempDir Path dir;
    MockWebServer api = new MockWebServer();
    final List<String> audit = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void start() throws Exception {
        api.start();
        Files.writeString(
                dir.resolve("s.json"),
                """
                {"openapi":"3.0.3","info":{"title":"Shop","version":"1"},
                 "paths":{"/orders":{"get":{"operationId":"listOrders","responses":{"200":{"description":"ok"}}}},
                          "/refunds":{"post":{"operationId":"createRefund","responses":{"200":{"description":"ok"}}}},
                          "/things":{"get":{"operationId":"listThings","responses":{"200":{"description":"ok"}}}}}}
                """);
        for (int i = 0; i < 4; i++)
            api.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));
    }

    @AfterEach
    void stop() throws Exception {
        api.shutdown();
    }

    private Tool build(String declaration) throws Exception {
        var script = EffectsFixture.parse(declaration + "\nworkflow Main() { note \"x\" }");
        var def = script.getTools().get(0);
        EffectContext recording = recording();
        return new ToolFactory().create(def, key -> null, dir, recording);
    }

    private EffectContext recording() {
        EffectContext noop = EffectContext.noop();
        return new EffectContext() {
            @Override
            public void audit(String event, Map<String, Object> data) {
                audit.add(event + " " + data.get("target"));
            }

            @Override
            public void trace(String text, Map<String, Object> data) {}

            @Override
            public io.github.llm4j.agent.tool.EffectJournal journal() {
                return noop.journal();
            }

            @Override
            public String currentStep() {
                return "main/s0";
            }

            @Override
            public long attempt() {
                return 1;
            }

            @Override
            public java.util.Set<Path> reservedPaths() {
                return java.util.Set.of();
            }

            @Override
            public io.github.llm4j.ratelimit.Sleeper sleeper() {
                return d -> {};
            }

            @Override
            public java.time.Clock clock() {
                return java.time.Clock.systemUTC();
            }
        };
    }

    private String declaration(String options) {
        return "tool Shop { use: openapi spec: \"s.json\" base_url: \"http://localhost:"
                + api.getPort()
                + "\" allow_http: true "
                + options
                + " }";
    }

    private long effects() {
        return audit.stream().filter(e -> e.startsWith("tool_effect")).count();
    }

    @Test
    void aDeclaredReadIsNotAnEffectAndADeclaredChangeIs() throws Exception {
        Tool shop = build(declaration("effects: { listOrders: reads, createRefund: changes }"));

        shop.execute(Map.of("endpoint", "/orders", "method", "GET"));
        assertThat(effects()).isZero();

        shop.execute(Map.of("endpoint", "/refunds", "method", "POST"));
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void anOperationTheMapDoesNotListFallsBackToTheToolsOwnEffect() throws Exception {
        Tool shop = build(declaration("effect: changes effects: { listOrders: reads }"));

        shop.execute(Map.of("endpoint", "/things", "method", "GET"));

        assertThat(effects()).as("listThings is a GET, but the tool says changes").isEqualTo(1);
    }

    @Test
    void withNothingDeclaredTheMethodDecides() throws Exception {
        Tool shop = build(declaration(""));

        shop.execute(Map.of("endpoint", "/orders", "method", "GET"));
        assertThat(effects()).isZero();
        shop.execute(Map.of("endpoint", "/refunds", "method", "POST"));
        assertThat(effects()).isEqualTo(1);
    }

    @Test
    void aMapEntryBeatsTheMethodEvenForAGet() throws Exception {
        Tool shop = build(declaration("effects: { listOrders: changes }"));

        shop.execute(Map.of("endpoint", "/orders", "method", "GET"));

        assertThat(effects()).isEqualTo(1);
    }
}
