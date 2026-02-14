# Knowledge Graphs

Knowledge Graphs enable your agents to reason over structured knowledge using entity-relationship representations.

## Overview

Knowledge Graphs allow agents to:

- Store and query structured knowledge
- Understand relationships between entities
- Traverse complex relationship networks
- Answer questions requiring multi-hop reasoning

## Core Concepts

### Entity

An entity represents a node in the graph with:

- **ID**: Unique identifier
- **Type**: Category (Person, Company, Product, etc.)
- **Properties**: Key-value attributes

### Relation

A relation represents an edge between entities:

- **Type**: Relationship type (WORKS_FOR, KNOWS, LOCATED_IN, etc.)
- **Properties**: Optional relationship attributes

### Triple

A triple is a subject-predicate-object statement:

```
(Alice) -[WORKS_FOR]-> (Acme Corp)
```

## Quick Start

### 1. Create Knowledge Graph

```java
import io.github.llm4j.agent.knowledge.*;
import io.github.llm4j.agent.knowledge.model.*;
import io.github.llm4j.agent.knowledge.store.*;

KnowledgeGraph graph = new InMemoryGraphStore();
```

### 2. Add Entities

```java
Entity alice = Entity.builder()
    .id("alice")
    .type("Person")
    .addProperty("name", "Alice Johnson")
    .addProperty("title", "CEO")
    .addProperty("email", "alice@example.com")
    .build();

Entity acmeCorp = Entity.builder()
    .id("acme")
    .type("Company")
    .addProperty("name", "Acme Corporation")
    .addProperty("industry", "Technology")
    .build();

graph.addEntity(alice);
graph.addEntity(acmeCorp);
```

### 3. Add Relationships

```java
Relation worksFor = Relation.builder()
    .type("WORKS_FOR")
    .addProperty("since", 2020)
    .addProperty("role", "CEO")
    .build();

Triple triple = new Triple(alice, worksFor, acmeCorp);
graph.addTriple(triple);
```

### 4. Create Agent with Graph Tool

```java
import io.github.llm4j.agent.knowledge.tools.*;

ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(new GraphQueryTool(graph))
    .build();

AgentResult result = agent.run("Who is the CEO of Acme Corporation?");
```

## Querying the Graph

### Direct Queries

```java
// Get entity by ID
Entity entity = graph.getEntity("alice");

// Find entities by type
List<Entity> people = graph.findEntities("Person", null);

// Find entities with filters
Map<String, Object> filters = Map.of("title", "CEO");
List<Entity> ceos = graph.findEntities("Person", filters);

// Get all relationships from an entity
List<Triple> aliceRelations = graph.getTriples("alice");

// Find specific relationships
List<Triple> worksForRelations = graph.findTriples(null, "WORKS_FOR", null);
```

### Agent Queries

The `GraphQueryTool` allows agents to query using natural language:

```java
// Query by entity ID
agent.run("Tell me about alice");

// Query by entity type
agent.run("Who are all the people in the graph?");

// Query relationships
agent.run("Who does Bob report to?");
agent.run("What companies does Alice work for?");
```

## Example: Company Org Chart

```java
// Build org chart
KnowledgeGraph graph = new InMemoryGraphStore();

// Add employees
Entity ceo = Entity.builder()
    .id("alice")
    .type("Person")
    .addProperty("name", "Alice Johnson")
    .addProperty("title", "CEO")
    .build();

Entity cto = Entity.builder()
    .id("bob")
    .type("Person")
    .addProperty("name", "Bob Smith")
    .addProperty("title", "CTO")
    .build();

Entity engineer = Entity.builder()
    .id("charlie")
    .type("Person")
    .addProperty("name", "Charlie Davis")
    .addProperty("title", "Senior Engineer")
    .build();

// Add reporting relationships
graph.addTriple(new Triple(
    cto,
    Relation.builder().type("REPORTS_TO").build(),
    ceo
));

graph.addTriple(new Triple(
    engineer,
    Relation.builder().type("REPORTS_TO").build(),
    cto
));

// Add collaboration relationships
graph.addTriple(new Triple(
    cto,
    Relation.builder().type("COLLABORATES_WITH").build(),
    engineer
));

// Query through agent
ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(new GraphQueryTool(graph))
    .build();

agent.run("Who reports to the CTO?");
agent.run("What is the reporting chain from Charlie to the CEO?");
```

## Graph Query Tool

The `GraphQueryTool` accepts JSON input with these patterns:

### Query by Entity ID

```json
{"entityId": "alice"}
```

### Query by Entity Type

```json
{"entityType": "Person"}
```

### Query Relationships

```json
{"subjectId": "bob"}
```

### Query Specific Relationship Type

```json
{"subjectId": "bob", "predicateType": "REPORTS_TO"}
```

## Best Practices

1. **Use Meaningful IDs**: Use descriptive, unique IDs for entities
2. **Consistent Types**: Use consistent entity and relation types
3. **Rich Properties**: Add relevant properties to entities and relations
4. **Bidirectional Relations**: Add both directions if needed (A→B and B→A)
5. **Metadata**: Include temporal or contextual metadata in relations
6. **Graph Size**: In-memory store suitable for <10K entities

## Advanced Patterns

### Multi-hop Queries

```java
// Find all people who report to someone who reports to the CEO
List<Triple> directReports = graph.findTriples(null, "REPORTS_TO", "ceo");
for (Triple triple : directReports) {
    String managerId = triple.getSubject().getId();
    List<Triple> teamMembers = graph.findTriples(null, "REPORTS_TO", managerId);
    // Process team members...
}
```

### Combining with Personas

```java
ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(new GraphQueryTool(graph))
    .persona(PersonaLibrary.businessConsultant())  // Strategic analysis
    .build();

agent.run("Analyze the organizational structure and suggest improvements");
```

### Combining with RAG

```java
// Use knowledge graph for structured data
// Use RAG for unstructured documents
// Combine both for comprehensive knowledge base

ReActAgent agent = ReActAgent.builder()
    .llmClient(client)
    .addTool(new GraphQueryTool(graph))
    .addTool(new DocumentSearchTool(ragSystem))  // Custom tool
    .build();
```

## Production Considerations

### In-Memory Graph Store

- **Pros**: Fast, simple, good for development
- **Cons**: Data lost on restart, limited scalability
- **Best For**: <10K entities, development, testing

### Future Integrations

For production use, consider:

- **RDF4J**: Semantic web, SPARQL queries, RDF standards
- **Neo4j**: Property graphs, Cypher queries, graph algorithms
- **Custom Backend**: Your existing graph database

## See Also

- [Creating Custom Tools](Creating-Custom-Tools.md) - Building custom tools
- [Agent Personas](Agent-Personas.md) - Configuring agent behavior
- [RAG Support](RAG-Support.md) - Document-based retrieval
- [Examples](../src/main/java/io/github/llm4j/examples/KnowledgeGraphExample.java) - Working examples
