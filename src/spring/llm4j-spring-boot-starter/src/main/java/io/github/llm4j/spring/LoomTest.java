package io.github.llm4j.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * On a test's configuration: swaps the models for {@link ScriptedLoomModels}, so the application's
 * integration tests run workflows with no model and no key while the rest of the context is real.
 * Inject {@link ScriptedLoomModels} to script what the agents say.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Import(LoomTestConfiguration.class)
public @interface LoomTest {}
