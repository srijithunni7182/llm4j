import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;
import java.util.Arrays;

public class ListModels {
    public static void main(String[] args) {
        // The key comes from the caller's environment; a key never belongs in source.
        String apiKey = System.getenv("GOOGLE_API_KEY");
        if (apiKey == null || apiKey.isEmpty()) {
            System.err.println("Set GOOGLE_API_KEY to list the available models.");
            return;
        }
        LLMConfig config = LLMConfig.builder().apiKey(apiKey).build();
        GoogleProvider provider = new GoogleProvider(config);
        try {
            System.out.println("Available models:");
            String[] models = provider.listModels();
            for (String m : models) {
                System.out.println(" - " + m);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
