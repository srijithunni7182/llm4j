package io.github.llm4j.loom.ast;

/**
 * A model provider declared in the script:
 * {@code provider Box { use: ollama  base_url: "http://gpu-box:11434" }}; agents then use
 * {@code model: "Box/llama3"}. Same shape as a tool declaration: a kind and options.
 */
public class ProviderDef extends ToolDef {
    public ProviderDef(String name) {
        super(name);
    }
}
