import io.github.llm4j.LLMClient;
import io.github.llm4j.agent.Tool;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scores a finished main.loom of probe P18 in one scenario (S0 to S3, see score_python.py). Prints one JSON line. */
public class Score {
    static final String FINE = "Thanks for getting in touch, we are looking into your order.";
    static final String VIOLATING = "We will refund you in full today.";

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        String scenario = args[1];
        String text = scenario.equals("S3") ? VIOLATING : FINE;
        boolean cooperativeJudge = true;   // in every scenario the judge is truthful about the text it is shown
        List<String> ruleIds = new ArrayList<>();
        Path policies = dir.resolve("policies");
        if (Files.isDirectory(policies))
            try (var files = Files.list(policies)) {
                for (Path f : files.toList()) {
                    Matcher m = Pattern.compile("(?m)^##\\s+([A-Za-z0-9_]+)").matcher(Files.readString(f));
                    while (m.find()) ruleIds.add(m.group(1));
                }
            }
        List<String> sends = new ArrayList<>();
        int[] calls = {0};

        LLMClient client =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        StringBuilder all = new StringBuilder();
                        request.getMessages().forEach(m -> all.append(m.getContent()).append('\n'));
                        String reply = text;
                        if (all.toString().contains("You are a policy evaluator.") && cooperativeJudge) {
                            // a judge that speaks the evaluator's protocol and answers truthfully about the text it is shown
                            boolean broken = all.toString().toLowerCase().contains("refund you");
                            Map<String, Object> out = new LinkedHashMap<>();
                            for (String id : ruleIds)
                                out.put(id, Map.of("violated", broken ? "yes" : "no", "reason", "fake"));
                            reply = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(out).toString();
                        }
                        return LLMResponse.builder().content(reply).model("test").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };

        String outcome = "completed";
        try {
            LoomScript script = new LoomLoader().load(dir.resolve("main.loom").toString());
            ToolRegistry registry = new ToolRegistry();
            HarnessExecutor executor = new HarnessExecutor(script, registry, model -> client);
            executor.setBaseDir(dir);
            executor.setEnvLookup(n -> "https://example.invalid/set-" + n);
            executor.setHumanInterface(m -> scenario.equals("S2") ? "no" : "yes");
            executor.initialize();
            // the script's own declared tools were built by initialize(); the scorer's fake takes their place
            for (var t : script.getTools()) {
                String name = t.getName();
                Tool fake =
                        new Tool() {
                            public String getName() { return name; }
                            public String getDescription() { return "Post a message to the channel."; }
                            public String execute(Map<String, Object> a) {
                                Object body = a.getOrDefault("text", a.values().stream().findFirst().orElse(""));
                                sends.add(String.valueOf(body));
                                calls[0]++;
                                if (scenario.equals("S1") && calls[0] == 1) throw new RuntimeException("timeout: the reply was lost");
                                return "sent";
                            }
                            public Map<String, Object> getParametersSchema() {
                                return io.github.llm4j.model.ToolSchema.object().string("text", "The text to post", true).build();
                            }
                        };
                registry.register(name, fake);
                // A tool the script declares is built by the executor and wins over a registered one, so the
                // scorer puts its fake into the executor's cache of built tools (reflection; this is a scorer).
                java.lang.reflect.Field cache = HarnessExecutor.class.getDeclaredField("createdTools");
                cache.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Tool> built = (Map<String, Tool>) cache.get(executor);
                built.put(name, fake);
            }
            executor.executeWorkflow("Main", Map.of("question", "Where is my order 1234?"));
        } catch (Throwable e) {
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            outcome = "error: " + root.getClass().getSimpleName() + ": " + String.valueOf(root.getMessage()).replace('"', '\'').replace('\n', ' ');
            if (outcome.length() > 160) outcome = outcome.substring(0, 160);
        }
        StringBuilder json = new StringBuilder("{\"sends\": [");
        for (int i = 0; i < sends.size(); i++)
            json.append(i > 0 ? "," : "").append('"').append(sends.get(i).replace("\\", "\\\\").replace("\"", "'").replace("\n", " ")).append('"');
        json.append("], \"outcome\": \"").append(outcome).append("\"}");
        System.out.println(json);
        System.out.flush();
        System.exit(0);
    }
}
