# Creating Custom Tools

Learn how to create custom tools for the ReAct agent framework.

## Tool Interface

All tools implement the `Tool` interface:

```java
public interface Tool {
    String getName();           // Tool identifier
    String getDescription();    // What the tool does
    String execute(Map<String, Object> args) throws Exception;  // Tool logic
}
```

## Basic Custom Tool

### Simple Example: Text Reverser

```java
import io.github.llm4j.agent.Tool;
import java.util.Map;

public class TextReverserTool implements Tool {
    
    @Override
    public String getName() {
        return "TextReverser";
    }
    
    @Override
    public String getDescription() {
        return "Reverses the order of characters in text. " +
               "Input should be a JSON object with a 'text' field.";
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String input = (String) args.get("text");
        if (input == null || input.isEmpty()) {
            return "Error: No text provided";
        }
        
        return new StringBuilder(input).reverse().toString();
    }
}
```

### Using Your Custom Tool

```java
ReActAgent agent = ReActAgent.builder()
        .llmClient(client)
        .addTool(new TextReverserTool())
        .build();

AgentResult result = agent.run("Reverse the text: Hello World");
// Final Answer: dlroW olleH
```

## Advanced Examples

### Web Search Tool

```java
import io.github.llm4j.agent.Tool;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.util.Map;

public class WebSearchTool implements Tool {
    
    private final String apiKey;
    private final HttpClient httpClient;
    
    public WebSearchTool(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
    }
    
    @Override
    public String getName() {
        return "WebSearch";
    }
    
    @Override
    public String getDescription() {
        return "Search the web for current information. " +
               "Input should be a JSON object with a 'query' field.";
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String query = (String) args.get("query");
        if (query == null || query.trim().isEmpty()) {
            return "Error: No query provided";
        }

        // Build search API request
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.search.com/search?q=" + 
                                query + "&key=" + apiKey))
                .GET()
                .build();
        
        // Execute search
        HttpResponse<String> response = httpClient.send(
            request, 
            HttpResponse.BodyHandlers.ofString()
        );
        
        // Parse and return results
        return parseSearchResults(response.body());
    }
    
    private String parseSearchResults(String jsonResponse) {
        // Parse JSON and extract relevant information
        // Return formatted summary
        return "Search results...";
    }
}
```

### File Reader Tool

```java
import io.github.llm4j.agent.Tool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class FileReaderTool implements Tool {
    
    private final String baseDirectory;
    
    public FileReaderTool(String baseDirectory) {
        this.baseDirectory = baseDirectory;
    }
    
    @Override
    public String getName() {
        return "FileReader";
    }
    
    @Override
    public String getDescription() {
        return "Read contents of a text file. " +
               "Input should be a JSON object with a 'filename' field.";
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String filename = (String) args.get("filename");
        if (filename == null || filename.trim().isEmpty()) {
            return "Error: No filename provided";
        }

        // Security: prevent path traversal
        if (filename.contains("..") || filename.contains("/")) {
            return "Error: Invalid filename. Provide only the filename.";
        }
        
        Path filePath = Paths.get(baseDirectory, filename);
        
        // Check if file exists
        if (!Files.exists(filePath)) {
            return "Error: File not found: " + filename;
        }
        
        // Read file
        String content = Files.readString(filePath);
        
        // Limit size to avoid overwhelming the LLM
        if (content.length() > 5000) {
            return content.substring(0, 5000) + "\n... (truncated)";
        }
        
        return content;
    }
}
```

### Database Query Tool

```java
import io.github.llm4j.agent.Tool;
import java.sql.*;
import java.util.Map;

public class DatabaseQueryTool implements Tool {
    
    private final String connectionString;
    
    public DatabaseQueryTool(String connectionString) {
        this.connectionString = connectionString;
    }
    
    @Override
    public String getName() {
        return "DatabaseQuery";
    }
    
    @Override
    public String getDescription() {
        return "Query the database for information. " +
               "Input should be a JSON object with a 'query' field containing the natural language question.";
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String input = (String) args.get("query");
        if (input == null) return "Error: No query provided";

        // Convert natural language to SQL (you might use another LLM for this)
        String sql = convertToSQL(input);
        
        try (Connection conn = DriverManager.getConnection(connectionString);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            return formatResults(rs);
        }
    }
    
    private String convertToSQL(String naturalLanguage) {
        // Implementation to convert natural language to SQL
        // Could use another LLM call or pattern matching
        return "SELECT COUNT(*) FROM users";
    }
    
    private String formatResults(ResultSet rs) throws SQLException {
        StringBuilder result = new StringBuilder();
        ResultSetMetaData metadata = rs.getMetaData();
        int columnCount = metadata.getColumnCount();
        
        while (rs.next()) {
            for (int i = 1; i <= columnCount; i++) {
                result.append(metadata.getColumnName(i))
                      .append(": ")
                      .append(rs.getString(i))
                      .append("\n");
            }
        }
        
        return result.toString();
    }
}
```

## Best Practices

### 1. Clear Descriptions

```java
// Good: Specific and clear about JSON input
@Override
public String getDescription() {
    return "Convert temperature from Celsius to Fahrenheit. " +
           "Input should be a JSON object with a 'celsius' field (number).";
}

// Bad: Vague
@Override
public String getDescription() {
    return "Temperature conversion";
}
```

### 2. Input Validation

```java
@Override
public String execute(Map<String, Object> args) throws Exception {
    // Validate input
    if (!args.containsKey("celsius")) {
        return "Error: Missing 'celsius' field";
    }
    
    Object val = args.get("celsius");
    double celsius;
    
    try {
        if (val instanceof Number) {
            celsius = ((Number) val).doubleValue();
        } else {
            celsius = Double.parseDouble(val.toString());
        }
    } catch (NumberFormatException e) {
        return "Error: 'celsius' must be a number";
    }
    
    // Execute tool logic
    double fahrenheit = (celsius * 9/5) + 32;
    
    return String.format("%.2f°F", fahrenheit);
}
```

### 3. Error Handling

```java
@Override
public String execute(Map<String, Object> args) throws Exception {
    try {
        // Tool logic
        return performOperation(args);
    } catch (NumberFormatException e) {
        return "Error: Invalid number format";
    } catch (IOException e) {
        return "Error: Failed to access resource: " + e.getMessage();
    } catch (Exception e) {
        return "Error: " + e.getMessage();
    }
}
```

### 4. Security Considerations

```java
@Override
public String execute(Map<String, Object> args) throws Exception {
    String input = (String) args.get("input");
    
    // Sanitize input
    String sanitized = input.replaceAll("[^a-zA-Z0-9\\s]", "");
    
    // Prevent path traversal
    if (input.contains("..") || input.contains("/")) {
        return "Error: Invalid input";
    }
    
    // Limit resource usage
    if (input.length() > 1000) {
        return "Error: Input too long";
    }
    
    // Proceed with operation
    return doWork(sanitized);
}
```

### 5. Performance

```java
@Override
public String execute(Map<String, Object> args) throws Exception {
    String key = args.toString(); // Simple cache key
    
    // Cache expensive operations
    if (cache.containsKey(key)) {
        return cache.get(key);
    }
    
    // Limit output size
    String result = performExpensiveOperation(args);
    
    if (result.length() > 2000) {
        result = result.substring(0, 2000) + "... (truncated)";
    }
    
    cache.put(key, result);
    return result;
}
```

## Testing Your Tool

### Unit Test Example

```java
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class TemperatureConverterToolTest {
    
    private final TemperatureConverterTool tool = 
        new TemperatureConverterTool();
    
    @Test
    void testConversion() throws Exception {
        String result = tool.execute(Map.of("celsius", 0));
        assertThat(result).isEqualTo("32.00°F");
        
        result = tool.execute(Map.of("celsius", 100));
        assertThat(result).isEqualTo("212.00°F");
    }
    
    @Test
    void testInvalidInput() throws Exception {
        String result = tool.execute(Map.of("celsius", "abc"));
        assertThat(result).contains("Error");
    }
    
    @Test
    void testEmptyInput() throws Exception {
        String result = tool.execute(Map.of());
        assertThat(result).contains("Error");
    }
}
```

### Integration Test with Agent

```java
@Test
void testToolWithAgent() {
    LLMClient mockClient = // create mock
    
    ReActAgent agent = ReActAgent.builder()
            .llmClient(mockClient)
            .addTool(new TemperatureConverterTool())
            .build();
    
    AgentResult result = agent.run(
        "Convert 25 degrees Celsius to Fahrenheit"
    );
    
    assertThat(result.isCompleted()).isTrue();
    assertThat(result.getFinalAnswer()).contains("77");
}
```

## Tool Composition

Combine multiple tools for complex functionality:

```java
public class EmailSenderTool implements Tool {
    
    private final Tool userLookupTool;
    private final EmailService emailService;
    
    public EmailSenderTool(Tool userLookupTool, 
                          EmailService emailService) {
        this.userLookupTool = userLookupTool;
        this.emailService = emailService;
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        // Parse input: "send email to John about meeting"
        String recipientName = (String) args.get("recipient");
        String subject = (String) args.get("subject");
        
        // Use another tool to lookup email
        String email = userLookupTool.execute(Map.of("name", recipientName));
        
        // Send email
        emailService.send(email, subject, "Generated content");
        
        return "Email sent successfully to " + recipientName;
    }
}
```

## Example: Complete Weather Tool

```java
import io.github.llm4j.agent.Tool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.*;
import java.util.Map;

public class WeatherTool implements Tool {
    
    private final String apiKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    
    public WeatherTool(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }
    
    @Override
    public String getName() {
        return "Weather";
    }
    
    @Override
    public String getDescription() {
        return "Get current weather for a city. " +
               "Input should be a JSON object with a 'city' field.";
    }
    
    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String city = (String) args.get("city");
        
        // Validate input
        if (city == null || city.trim().isEmpty()) {
            return "Error: Please provide a city name";
        }
        
        // Build API request
        String url = String.format(
            "https://api.weather.com/current?city=%s&key=%s",
            city, apiKey
        );
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        try {
            // Make API call
            HttpResponse<String> response = httpClient.send(
                request, 
                HttpResponse.BodyHandlers.ofString()
            );
            
            if (response.statusCode() != 200) {
                return "Error: Could not fetch weather for " + city;
            }
            
            // Parse JSON response
            JsonNode json = objectMapper.readTree(response.body());
            
            // Extract weather data
            String temp = json.get("temperature").asText();
            String condition = json.get("condition").asText();
            String humidity = json.get("humidity").asText();
            
            // Format response
            return String.format(
                "Weather in %s: %s°C, %s, Humidity: %s%%",
                city, temp, condition, humidity
            );
            
        } catch (Exception e) {
            return "Error fetching weather: " + e.getMessage();
        }
    }
}
```

## Next Steps

- **[ReAct Agent](ReAct-Agent)** - Learn more about the agent framework
- **[Examples](Examples)** - See complete examples
- **[API Reference](API-Reference)** - Complete API documentation
