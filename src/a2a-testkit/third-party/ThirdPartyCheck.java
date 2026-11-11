import io.github.llm4j.a2a.*;
import io.github.llm4j.secret.SecretRef;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Scratch check of llm4j's A2A helper and RemoteAgent against a third-party agent on loopback. */
public class ThirdPartyCheck {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static String base;

    static String get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }

    static String rpc(String url, A2AJsonRpc10.Request r) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json");
        r.headers().forEach(b::header);
        var resp = HTTP.send(b.POST(HttpRequest.BodyPublishers.ofString(r.body())).build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    /** The transport the mock tests use: it hands back a card whose address passes the endpoint guard, and sends to the real port. */
    static class RewritingTransport implements A2ATransport {
        final String publicBase = "https://agent.example.test";
        long ids;
        public AgentCardView fetchCard(String address) {
            try {
                var card = A2AJsonRpc10.parseCard(ThirdPartyCheck.get(base + "/.well-known/agent-card.json"));
                var rewritten = card.endpoints().stream().map(e -> new AgentCardView.Endpoint(publicBase + "/rpc", e.binding(), e.protocolVersion(), e.tenant())).toList();
                return new AgentCardView(card.name(), card.description(), card.skills(), rewritten, card.defaultInputMediaTypes(), card.extensions(), card.signature(), card.authSchemes());
            } catch (Exception e) { throw new A2AException("card: " + e, e); }
        }
        String call(A2AJsonRpc10.Request r) { try { return rpc(base, r); } catch (Exception e) { throw new A2AException("rpc: " + e, e); } }
        public SendResult send(AgentCardView.Endpoint ep, SecretRef c, A2AMessage m, SendOptions o) { return A2AJsonRpc10.parseSendResponse(call(A2AJsonRpc10.sendMessage(++ids, m, o, ep.tenant()))); }
        public RemoteTask get(AgentCardView.Endpoint ep, SecretRef c, String id) { return A2AJsonRpc10.parseGetTaskResponse(call(A2AJsonRpc10.getTask(++ids, id, 10, ep.tenant()))); }
        public RemoteTask cancel(AgentCardView.Endpoint ep, SecretRef c, String id) { return A2AJsonRpc10.parseGetTaskResponse(call(A2AJsonRpc10.cancelTask(++ids, id, ep.tenant()))); }
        public List<RemoteTask> recentTasks(AgentCardView.Endpoint ep, SecretRef c, Instant since, int h) { return A2AJsonRpc10.parseListTasksResponse(call(A2AJsonRpc10.listTasks(++ids, since, h, 50, ep.tenant()))); }
        public boolean supports(AgentCardView.Endpoint ep) { return A2AJsonRpc10.supports(ep); }
        public Set<String> supportedExtensions() { return Set.of(); }
    }

    public static void main(String[] a) throws Exception {
        base = a[0];
        System.out.println("== 1. the helper reads the real card");
        var card = A2AJsonRpc10.parseCard(get(base + "/.well-known/agent-card.json"));
        System.out.println("name=" + card.name() + " endpoints=" + card.endpoints() + " inputTypes=" + card.defaultInputMediaTypes() + " signature=" + card.signature() + " skills=" + card.skills().size());
        System.out.println("supports(endpoint)=" + A2AJsonRpc10.supports(card.endpoints().get(0)));

        System.out.println("== 2. the endpoint guard refuses the real card (http, loopback) through RemoteAgent");
        try {
            RemoteAgent.builder(base + "/.well-known/agent-card.json", new RewritingTransport() {
                @Override public AgentCardView fetchCard(String address) { return A2AJsonRpc10.parseCard(uncheckedGet()); }
            }, (c, m) -> new ChannelResponse.Refuse("x")).build().card();
            System.out.println("NOT REFUSED (unexpected)");
        } catch (A2AException e) { System.out.println("refused: " + e.getMessage()); }

        System.out.println("== 3. send through the helper, parse the reply");
        var msg = A2AMessage.user("m-" + System.nanoTime(), List.of(new Part.Text("hi")));
        String raw = rpc(card.endpoints().get(0).url(), A2AJsonRpc10.sendMessage(1, msg, SendOptions.immediate(), null));
        System.out.println("raw=" + raw.substring(0, Math.min(raw.length(), 600)));
        var result = A2AJsonRpc10.parseSendResponse(raw);
        System.out.println("parsed=" + result);

        System.out.println("== 4. through RemoteAgent (card address rewritten to https so the guard passes)");
        var agent = RemoteAgent.builder("https://agent.example.test/.well-known/agent-card.json", new RewritingTransport(), (c, m) -> new ChannelResponse.Refuse("not used")).build();
        var sent = agent.send(A2AMessage.user("m-" + System.nanoTime(), List.of(new Part.Text("hello again"))));
        System.out.println("send=" + sent);
        String taskId = sent instanceof SendResult.TaskResult t ? t.task().id() : null;
        if (taskId != null) {
            var again = agent.get(taskId);
            System.out.println("get state=" + again.state() + " artifacts=" + again.artifacts());
            var found = agent.findBySentMessage(((SendResult.TaskResult) sent).task().history().isEmpty() ? "none" : ((SendResult.TaskResult) sent).task().history().get(0).messageId(), Instant.now().minusSeconds(60));
            System.out.println("findBySentMessage=" + found.map(RemoteTask::id));
            try { System.out.println("cancel on a finished task: " + agent.cancel(taskId).state()); } catch (A2AException e) { System.out.println("cancel refused: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
        }
        System.out.println("== 5. an unknown task");
        try { agent.get("no-such-task"); } catch (A2AException e) { System.out.println("get unknown: " + e.getClass().getSimpleName() + " " + e.getMessage()); }
    }

    static String uncheckedGet() { try { return get(base + "/.well-known/agent-card.json"); } catch (Exception e) { throw new RuntimeException(e); } }
}
