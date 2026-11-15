# Connecting a system

You have an API, an MCP server, a program or a documentation page, and you want a workflow to use it **without** handing the
model a blank cheque. `weave connect` writes the declarations, with each operation's effect proposed and approved, a pin that
notices when the system changes, and the audit run on the result.

[Home](Home.md) · [Loom guide: tools](../src/loom/ai-agent4j-loom/LOOM_GUIDE.md#connecting-a-system-weave-connect) · [Securing integrations](../docs/security/integrations.md) · [Tools catalog](../docs/TOOLS_CATALOG.md)

## Pick the one that matches what you have

| You have | Run | What you get |
|---|---|---|
| An OpenAPI description (a file or an https address) | `weave connect openapi specs/petstore.json --read-only --write main.loom` | One `openapi` tool per chosen operation, each with `effect:`, `on_unknown:` and a `pin:` |
| An MCP server (a program or an https address) | `weave connect mcp npx --arg -y --arg @example/files --isolation container --image node:22 --write main.loom` | An `mcp` block with `isolation:`, `effects:` and a `pin:` |
| A command-line program | `weave connect command Resize --help-file convert.help --image dpokidov/imagemagick:7 --workspace-tool Photos --samples samples.json --write main.loom` | A typed `command` tool a model drafted and your samples confirmed |
| An API with only a documentation page | `weave connect http Orders --docs orders.md --base-url https://api.example.com --auth invoker.token --write main.loom` | An `http` tool limited to the methods and paths the page shows |

Without `--write` nothing is written: it prints what it would write.

## What it will not do

- **It does not decide an effect for you.** A `GET` is proposed as `reads`; anything else as `changes`; an MCP annotation is the
  server's own, untrusted word. Nothing is written until you approve the change you are shown (`--yes` is that approval).
- **It never writes a secret.** Where a credential goes it writes `env.NAME`, `secret.NAME` or `invoker.NAME`, and a value
  given in their place is refused.
- **It never writes a result that fails `weave check`, or that has a new high audit finding.**
- **A model never decides what matters for safety.** For `command` and `http` the model proposes a template and parameter types,
  or methods and paths, from text it is told is untrusted; the isolation, image, network, workspace, address and credential come
  from your flags. The draft must answer your own samples, and one that still fails after its repairs is never written.

## Keeping it true

- `weave check` compares a local description with each `pin:` (LA35) and warns when it changed.
- In CI, `weave connect openapi main.loom --refresh --check` reads the live description again, prints what changed and exits 1 on
  drift; `weave connect mcp main.loom --refresh --check` does the same for each pinned server. Neither writes.
- `weave doctor --network` makes one TLS connection to each host of your integrations, with no request and no credential.
- `weave capture` records what each tool answered in a real run, masked, so the integration can be tested without the system.
