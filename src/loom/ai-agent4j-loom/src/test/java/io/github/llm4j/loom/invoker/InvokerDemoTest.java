package io.github.llm4j.loom.invoker;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.loom.lexer.Lexer;
import io.github.llm4j.loom.parser.LoomParser;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import io.github.llm4j.secret.InvokerCredentials;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scenario this feature is for, end to end (gate G5): a web application, two people who log in with OAuth, one Loom script. Each person's workflow
 * calls the refunds API as that person; one person's access token runs out in the middle and is replaced with the refresh token, and the other's is untouched.
 * Prints the transcript that is kept as evidence.
 */
class InvokerDemoTest {

    @TempDir
    Path dir;

    /** The identity provider and the refunds API, in one local server. */
    private static final class Backend implements AutoCloseable {
        final HttpServer server;
        final Map<String, String> accessTokenOwner = new ConcurrentHashMap<>();     // access token -> user
        final Map<String, String> refreshTokenOwner = new ConcurrentHashMap<>();    // refresh token -> user
        final Map<String, Integer> uses = new ConcurrentHashMap<>();                // access token -> calls it has served
        final Map<String, Integer> usesAllowed = new ConcurrentHashMap<>();         // access token -> calls before it expires
        final AtomicInteger issued = new AtomicInteger();
        final List<String> transcript = new CopyOnWriteArrayList<>();

        Backend() throws IOException {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/oauth/login", x -> reply(x, 200, login(query(x, "user"))));
            server.createContext("/oauth/refresh", x -> {
                String user = refreshTokenOwner.get(query(x, "refresh_token"));
                if (user == null) reply(x, 400, "invalid_grant");
                else {
                    String access = mint(user, Integer.MAX_VALUE);
                    transcript.add("idp   refresh for " + user + " -> new access token");
                    reply(x, 200, access);
                }
            });
            server.createContext("/refunds", x -> {
                String auth = x.getRequestHeaders().getFirst("Authorization");
                String token = auth == null ? "" : auth.replaceFirst("^Bearer ", "");
                String user = accessTokenOwner.get(token);
                int served = uses.merge(token, 1, Integer::sum);
                if (user == null || served > usesAllowed.getOrDefault(token, 0)) {
                    transcript.add("api   401 for " + (user == null ? "unknown" : user) + "'s token " + short_(token));
                    reply(x, 401, "token expired");
                } else {
                    transcript.add("api   200 for " + user + " (asked about " + query(x, "who") + ")");
                    reply(x, 200, "[{\"refund\": 1, \"customer\": \"" + user + "\"}]");
                }
            });
            server.start();
        }

        String url() {
            return "http://localhost:" + server.getAddress().getPort();
        }

        /** The user logs in: an access token that works for {@code allowedCalls} calls, and a refresh token. */
        private String login(String user) {
            int allowed = "ann".equals(user) ? 1 : Integer.MAX_VALUE; // ann's access token runs out after one call
            String access = mint(user, allowed);
            String refresh = "refresh-" + user + "-" + issued.incrementAndGet();
            refreshTokenOwner.put(refresh, user);
            transcript.add("idp   " + user + " logged in");
            return access + " " + refresh;
        }

        private String mint(String user, int allowedCalls) {
            String access = "access-" + user + "-" + issued.incrementAndGet();
            accessTokenOwner.put(access, user);
            usesAllowed.put(access, allowedCalls);
            return access;
        }

        private static String short_(String t) {
            return t.length() > 14 ? t.substring(0, 14) + "…" : t;
        }

        private static String query(com.sun.net.httpserver.HttpExchange x, String name) {
            String q = x.getRequestURI().getQuery();
            if (q != null) for (String pair : q.split("&")) if (pair.startsWith(name + "=")) return pair.substring(name.length() + 1);
            return "";
        }

        private static void reply(com.sun.net.httpserver.HttpExchange x, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(status, bytes.length);
            x.getResponseBody().write(bytes);
            x.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** The web application's session for one logged-in user: this is what a host writes. */
    private static final class UserSession implements InvokerCredentials {
        private final String idp;
        private final String user;
        private final String refreshToken;
        private volatile String accessToken;
        private final AtomicInteger refreshes = new AtomicInteger();

        UserSession(String idp, String user, String loginAnswer) {
            this.idp = idp;
            this.user = user;
            String[] tokens = loginAnswer.split(" ");
            this.accessToken = tokens[0];
            this.refreshToken = tokens[1];
        }

        @Override
        public String get(String name) {
            return "token".equals(name) ? "Bearer " + accessToken : null;
        }

        @Override
        public void invalidate(String name) { // the API said 401: use the refresh token to get a new access token
            accessToken = http(idp + "/oauth/refresh?refresh_token=" + refreshToken);
            refreshes.incrementAndGet();
        }

        @Override
        public boolean provides(String name) {
            return "token".equals(name);
        }

        @Override
        public String toString() {
            return "session of " + user;
        }
    }

    private static String http(String url) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString()).body();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final String SCRIPT = """
            tool Refunds { use: openapi  spec: "refunds.json"  auth_header: "Authorization"  auth_value: invoker.token }
            agent Support { model: "m"  system: "You are Support."  tools: [Refunds] }
            workflow Main(who) { delegate "look up refunds for {who}" to Support -> answer }
            """;

    private HarnessExecutor executorFor(Backend backend, String who) {
        // the model asks for the refunds of whoever the task names, three times, then answers
        LLMClient model = new LLMClient() {
            @Override
            public LLMResponse chat(LLMRequest r) {
                String all = r.getMessages().stream().map(m -> m.getContent()).reduce("", (a, b) -> a + "\n" + b);
                long calls = all.split("Observation:", -1).length - 1;
                String content = calls >= 3
                        ? "```json\n{\"thought\": \"t\", \"final_answer\": \"checked refunds for " + who + "\"}\n```"
                        : "```json\n{\"thought\": \"t\", \"action\": \"Refunds\", \"action_input\": {\"endpoint\": \"/refunds\", \"method\": \"GET\", \"parameters\": {\"who\": \""
                          + who + "\", \"page\": \"" + calls + "\"}}}\n```";
                return LLMResponse.builder().content(content).model("m").tokenUsage(10, 5, 15).build();
            }

            @Override
            public Stream<LLMResponse> chatStream(LLMRequest r) {
                return Stream.of(chat(r));
            }
        };
        HarnessExecutor executor = new HarnessExecutor(new LoomParser(new Lexer(SCRIPT).tokenize()).parseScript(), new ToolRegistry(), x -> model);
        executor.setEnvLookup(Map.<String, String>of()::get);
        executor.setBaseDir(dir);
        executor.initialize();
        return executor;
    }

    @Test
    @Tag("demo")
    void twoPeopleLogInAndEachWorkflowActsAsItsOwnPersonWhileOneTokenIsRefreshed() throws Exception {
        try (Backend backend = new Backend()) {
            Files.writeString(dir.resolve("refunds.json"), """
                    {"openapi": "3.0.0", "info": {"title": "Refunds", "version": "1"}, "servers": [{"url": "%s"}],
                     "paths": {"/refunds": {"get": {"summary": "List the refunds of a customer",
                       "parameters": [{"name": "who", "in": "query", "schema": {"type": "string"}}, {"name": "page", "in": "query", "schema": {"type": "string"}}],
                       "responses": {"200": {"description": "ok"}}}}}}
                    """.formatted(backend.url()));
            UserSession ann = new UserSession(backend.url(), "ann", http(backend.url() + "/oauth/login?user=ann"));
            UserSession bob = new UserSession(backend.url(), "bob", http(backend.url() + "/oauth/login?user=bob"));
            String annFirstToken = ann.accessToken;

            // the application runs one workflow for each of them at the same time, one executor for each run
            CompletableFuture<HarnessExecutor> forAnn = CompletableFuture.supplyAsync(() -> run(backend, "ann", ann));
            CompletableFuture<HarnessExecutor> forBob = CompletableFuture.supplyAsync(() -> run(backend, "bob", bob));

            assertThat(forAnn.get().getContext().getVariable("answer")).isEqualTo("checked refunds for ann");
            assertThat(forBob.get().getContext().getVariable("answer")).isEqualTo("checked refunds for bob");
            backend.transcript.forEach(line -> System.out.println("DEMO " + line));
            System.out.println("DEMO ann: " + ann.refreshes.get() + " refresh(es), access token changed: " + !ann.accessToken.equals(annFirstToken));
            System.out.println("DEMO bob: " + bob.refreshes.get() + " refresh(es)");

            assertThat(backend.transcript.stream().filter(l -> l.startsWith("api   200 for ann")).count()).isEqualTo(3);
            assertThat(backend.transcript.stream().filter(l -> l.startsWith("api   200 for bob")).count()).isEqualTo(3);
            assertThat(backend.transcript.stream().filter(l -> l.startsWith("api   401"))).hasSize(1).allMatch(l -> l.contains("ann"));
            assertThat(ann.refreshes).hasValue(1);
            assertThat(bob.refreshes).hasValue(0);
            assertThat(ann.accessToken).isNotEqualTo(annFirstToken);
            // no call ever carried one person's token for the other's question
            assertThat(backend.transcript.stream().filter(l -> l.startsWith("api   200")).allMatch(l -> l.contains("for ann") == l.contains("asked about ann"))).isTrue();
        }
    }

    private HarnessExecutor run(Backend backend, String who, InvokerCredentials session) {
        HarnessExecutor executor = executorFor(backend, who);
        executor.executeWorkflow("Main", Map.of("who", who), session);
        return executor;
    }
}
