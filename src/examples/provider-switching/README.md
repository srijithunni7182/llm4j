# Switching providers

The same question, asked of whichever vendor you choose, with a fallback chain across vendors. Nothing in the workflow changes when the vendor does: the
model name does.

```
export OPENAI_API_KEY=...          # and/or XAI_API_KEY, PERPLEXITY_API_KEY (a missing key makes that model fail the check, so set the ones you use)
./run.sh "What is the capital of France?"
weave check switch.loom             # lists any model whose key is missing
```

- `gpt-5-mini`, `xai/grok-3`, `kimi/kimi-k3`, `qwen/qwen3-max` and `perplexity/openai/gpt-5.6-terra` are resolved by name; the key comes from the environment
  (`OPENAI_API_KEY`, `XAI_API_KEY`, `MOONSHOT_API_KEY`, `DASHSCOPE_API_KEY`, `PERPLEXITY_API_KEY`) or a secret store, bound to the vendor's host
  (`weave secret set OPENAI_API_KEY` binds it to `api.openai.com`).
- `routing Vendors` tries the primary and falls back, in order, when a vendor is unavailable or rate limited.
- `provider Local { use: openai_compatible … }` reaches a local vLLM or LM Studio server; it needs no key.

Kimi and Qwen are **preview** until their fixtures are recorded from the real APIs. Perplexity here is plain model access: nothing searches the web unless a
step asks for grounding (coming).
