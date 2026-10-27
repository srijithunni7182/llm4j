package io.github.llm4j.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An interface whose methods are the script's workflows, made a bean over the starter's {@link
 * LoomRuntime} (set {@code llm4j.loom.script}). A method is named like the workflow (the name
 * bindgen gives it: {@code Main} is {@code main}), takes one {@code String} per workflow parameter
 * in order, and returns {@link io.github.llm4j.loom.host.RunOutcome}. Every method is checked
 * against the script when the application starts: one that matches no workflow stops the start,
 * naming the nearest one. For compile-time names and typed results, generate a facade with {@code
 * weave bindgen} and register it with {@link EnableLoomWorkflows}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface LoomWorkflows {}
