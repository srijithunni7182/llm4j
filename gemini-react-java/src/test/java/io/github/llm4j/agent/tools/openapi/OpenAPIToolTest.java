package io.github.llm4j.agent.tools.openapi;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpenAPIToolTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private OpenAPITool openAPITool;

    @BeforeEach
    void setUp() throws Exception {
        OpenAPIParameter param = OpenAPIParameter.builder()
                .name("q")
                .in("query")
                .description("Search query")
                .required(true)
                .build();
        OpenAPIEndpoint endpoint = OpenAPIEndpoint.builder()
                .path("/search")
                .method("GET")
                .summary("Search")
                .description("Performs a search.")
                .parameters(List.of(param))
                .build();
        OpenAPISpec spec = OpenAPISpec.builder()
                .title("Test API")
                .version("1.0")
                .description("A test API.")
                .servers(List.of("http://localhost"))
                .endpoints(List.of(endpoint))
                .build();

        openAPITool = OpenAPITool.builder()
                .name("TestAPI")
                .spec(spec)
                .build();

        // Use reflection to inject the mock client since the class creates its own
        java.lang.reflect.Field clientField = OpenAPITool.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        clientField.set(openAPITool, httpClient);
    }

    @Test
    void testExecute_GetRequest_Success() throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"result\": \"success\"}");

        Map<String, Object> args = Map.of(
                "endpoint", "/search",
                "method", "GET",
                "parameters", Map.of("q", "testing")
        );

        String result = openAPITool.execute(args);

        assertEquals("{\"result\": \"success\"}", result);
    }

    @Test
    void testExecute_MissingRequiredParameter() {
        Map<String, Object> args = Map.of(
                "endpoint", "/search",
                "method", "GET",
                "parameters", Collections.emptyMap() // 'q' is missing
        );

        String result = openAPITool.execute(args);

        assertTrue(result.contains("Error: Missing required parameter 'q'"));
    }

    @Test
    void testExecute_UnsafeCasting() {
        Map<String, Object> args = Map.of(
                "endpoint", 12345, // Not a string
                "method", "GET"
        );

        String result = openAPITool.execute(args);

        assertTrue(result.contains("Error: 'endpoint' and 'method' arguments must be strings"));
    }

    @Test
    void testExecute_PostRequest_WithBody() throws Exception {
        // Add a POST endpoint to the spec for this test
        OpenAPIEndpoint postEndpoint = OpenAPIEndpoint.builder()
                .path("/create")
                .method("POST")
                .summary("Create a resource")
                .build();
        OpenAPISpec spec = OpenAPISpec.builder()
                .title("Test API")
                .version("1.0")
                .servers(List.of("http://localhost"))
                .endpoints(List.of(postEndpoint)) // Use a spec with only the POST endpoint for clarity
                .build();

        openAPITool = OpenAPITool.builder().name("TestAPI").spec(spec).build();
        java.lang.reflect.Field clientField = OpenAPITool.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        clientField.set(openAPITool, httpClient);

        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponse);
        when(httpResponse.statusCode()).thenReturn(201);
        when(httpResponse.body()).thenReturn("{\"status\": \"created\"}");

        Map<String, Object> requestBody = Map.of("name", "new-item");
        Map<String, Object> args = Map.of(
                "endpoint", "/create",
                "method", "POST",
                "body", requestBody
        );

        String result = openAPITool.execute(args);

        assertEquals("{\"status\": \"created\"}", result);
    }
}
