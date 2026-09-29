# Architecture Design Patterns
- Prefer the simplest architecture that meets the stated non-functional requirements; justify every extra component.
- Draw boundaries around data ownership; each service owns its data and exposes it through an API.
- Choose synchronous calls for queries and asynchronous messaging for workflows that must survive failures.
- Design for failure: timeouts, retries with backoff, idempotent writes, and a plan for partial outages.
- Record each significant decision as an ADR: context, options considered, decision, consequences.
