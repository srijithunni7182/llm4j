import io.github.llm4j.LLMClient;
import io.github.llm4j.loom.ast.LoomScript;
import io.github.llm4j.loom.execution.HarnessExecutor;
import io.github.llm4j.loom.execution.LoomLoader;
import io.github.llm4j.loom.execution.ToolRegistry;
import io.github.llm4j.model.LLMRequest;
import io.github.llm4j.model.LLMResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Runs main.loom with a recording model and prints whether the extractor's request held the earlier step's output. */
public class Probe {
    static final String EARLIER = "EARLIER-SECRET-NOTE";

    public static void main(String[] args) throws Exception {
        List<String> requests = new ArrayList<>();
        LLMClient client =
                new LLMClient() {
                    @Override
                    public LLMResponse chat(LLMRequest request) {
                        StringBuilder all = new StringBuilder();
                        request.getMessages().forEach(m -> all.append(m.getContent()).append('\n'));
                        requests.add(all.toString());
                        boolean reads = all.toString().contains("Read the invoice");
                        String reply = reads ? "{\"vendor\":\"Acme\",\"total\":240}" : EARLIER;
                        return LLMResponse.builder().content(reply).model("test").build();
                    }

                    @Override
                    public java.util.stream.Stream<LLMResponse> chatStream(LLMRequest request) {
                        return java.util.stream.Stream.empty();
                    }
                };
        LoomScript script = new LoomLoader().load(args[0]);
        HarnessExecutor executor = new HarnessExecutor(script, new ToolRegistry(), model -> client);
        executor.initialize();
        executor.executeWorkflow("Main", Map.of("text", "Invoice from Acme, total 240"));
        for (int i = 0; i < requests.size(); i++) {
            String r = requests.get(i);
            boolean extractor = r.contains("<<<INPUT");
            System.out.println(
                    "request " + (i + 1) + " (" + (extractor ? "extractor" : r.contains("Read the invoice") ? "agent used as an extractor" : "agent") + ") contains the earlier step's output = " + r.contains(EARLIER));
        }
    }
}
