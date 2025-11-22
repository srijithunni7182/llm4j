# OpenAPI Tool

The `OpenAPITool` is a powerful, dynamic tool that allows your ReAct agents to automatically discover and interact with any REST API described by an OpenAPI (Swagger) specification.

Instead of manually creating a `Tool` class for every API endpoint, you simply provide the OpenAPI spec, and the agent figures out the rest!

## Features

- **Automatic Discovery**: Parses OpenAPI 3.0+ and Swagger 2.0 specs to find all available endpoints.
- **Dynamic Tooling**: Automatically generates tool descriptions and parameter requirements for the LLM.
- **Authentication**: Supports API Key (Query/Header) and Bearer Token authentication.
- **Smart Execution**: Handles parameter substitution (path, query) and executes HTTP requests automatically.
- **Response Handling**: Automatically truncates large responses to prevent context overflow.

## Usage

### 1. Add Dependency

Ensure you have the `swagger-parser` dependency (included in `gemini-react-java`):

```xml
<dependency>
    <groupId>io.swagger.parser.v3</groupId>
    <artifactId>swagger-parser</artifactId>
    <version>2.1.19</version>
</dependency>
```

### 2. Create the Tool

You can create an `OpenAPITool` from a URL, a local file, or a raw string.

```java
import io.github.llm4j.agent.tools.openapi.OpenAPITool;

// Example: AviationStack API
OpenAPITool aviationTool = OpenAPITool.builder()
    .name("AviationStack")
    .specLocation("https://api.aviationstack.com/openapi.json") // URL or File Path
    .apiKeyAuth("access_key", System.getenv("AVIATION_STACK_API_KEY")) // Auth
    .build();
```

### 3. Add to Agent

Add the tool to your `ReActAgent` just like any other tool.

```java
ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(aviationTool)
    .addTool(new CurrentTimeTool())
    .build();
```

### 4. Run the Agent

The agent can now use any endpoint defined in the spec!

```java
// The agent will discover the /flights endpoint and use it
AgentResult result = agent.run("What is the status of flight AA100?");
System.out.println(result.getFinalAnswer());
```

## Authentication Options

The `OpenAPITool` builder supports common authentication methods:

### API Key in Query Parameter
```java
.apiKeyAuth("access_key", "YOUR_KEY")
```

### API Key/Token in Header
```java
.headerAuth("Authorization", "Bearer YOUR_TOKEN")
.headerAuth("X-API-Key", "YOUR_KEY")
```

## How It Works

1. **Parsing**: The tool parses the OpenAPI spec and extracts all `GET`, `POST`, `PUT`, `DELETE` operations.
2. **Description**: It generates a comprehensive description for the LLM, listing all endpoints and their parameters.
3. **Planning**: The LLM uses this description to decide which endpoint to call and what parameters to provide.
4. **Execution**: The tool constructs the HTTP request, substituting path variables and adding query parameters/headers, then executes it using Java's `HttpClient`.
5. **Observation**: The API response is returned to the LLM as an observation.

## Best Practices

- **Filter Specs**: If an API has hundreds of endpoints, consider creating a smaller, focused OpenAPI spec with only the endpoints you need to avoid overwhelming the LLM.
- **Descriptions**: Ensure your OpenAPI spec has good `summary` and `description` fields for each endpoint/parameter, as the LLM relies on these to understand how to use the API.
