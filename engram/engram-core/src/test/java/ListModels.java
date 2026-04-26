import io.github.llm4j.config.LLMConfig;
import io.github.llm4j.provider.google.GoogleProvider;
import java.util.Arrays;

public class ListModels {
    public static void main(String[] args) {
        String apiKey = "AIzaSyCgP2Plnszh--vLe-KRDd0tZUOJ0N1ft_k";
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
