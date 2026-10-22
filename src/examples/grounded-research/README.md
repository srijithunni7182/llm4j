# Grounded research

A researcher that may search the web, and a writer that gets its notes and its sources.

```
export PERPLEXITY_API_KEY=... ANTHROPIC_API_KEY=...
./run.sh "What is the WHO guidance on daily sodium intake?"
java -jar ../../loom/ai-agent4j-loom/target/ai-agent4j-loom-6.0.0-cli.jar audit research.loom      # LA20: the researcher reads the open web
```

- `grounding: web { allow: [...] recency: year }` lets Perplexity search only those sites, for sources from the last year.
- `{notes_citations}` is the researcher's sources: `index`, `url`, `title`, `snippet`. Perplexity lists what it *consulted*; Claude and OpenAI list what they *cited*.
- `budget { searches: 4 }` on the agent, `budget 2 searches` on the step and `budget { searches: 10 }` on the run all stop a grounded call before it is made.
- The researcher has no tools and no private data, so the audit shows no trifecta; keep it that way.
