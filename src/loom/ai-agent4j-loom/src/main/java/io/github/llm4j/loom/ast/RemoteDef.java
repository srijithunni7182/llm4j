package io.github.llm4j.loom.ast;

/**
 * {@code remote Name { card: "https://…" auth: secret.KEY agent_channel: Desk timeout: 2h }}: an agent
 * that someone else runs, reached over A2A. There is no version setting and no {@code pii:} setting by
 * design. Which keys and values are allowed is checked at load time.
 */
public final class RemoteDef extends NamedSettings {

    public RemoteDef(String name) {
        super(name);
    }
}
