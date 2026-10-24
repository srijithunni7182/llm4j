package io.github.llm4j.loom.execution;

/**
 * What a step is about to ask of the outside world: a model, a classifier or a task. {@code input}
 * is what it is asked (the task text, the text to classify, the arguments); {@code config} is what
 * it is asked with (the model, the system prompt, the prompt version), so a recorded answer is only
 * reused for the same question put to the same agent in the same way.
 *
 * @param step the step id
 * @param kind {@code delegate}, {@code classify} or {@code task}
 * @param target the agent, classifier or task
 * @param input what it is asked
 * @param config the model and prompts it is asked with, as one string
 */
public record Ask(String step, String kind, String target, String input, String config) {}
