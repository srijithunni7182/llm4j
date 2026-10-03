/**
 * Ready-made pieces for evaluating an agent without spending money by accident: a {@link
 * io.github.llm4j.eval.testing.SpendGuard} that stops a run at a cap, a {@link
 * io.github.llm4j.eval.testing.ScriptedClient} and {@link io.github.llm4j.eval.testing.FakeJudge}
 * that stand in for real models, an {@link io.github.llm4j.eval.testing.AgentReplay} that remembers
 * agent runs so a repeated run pays only for what is missing, and a {@link
 * io.github.llm4j.eval.testing.RecordedSearchTool} that answers from recorded snippets.
 */
package io.github.llm4j.eval.testing;
