package io.github.llm4j.spring;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.context.annotation.Import;

/**
 * Registers the facades {@code weave bindgen} generated as beans: each is built with its {@code
 * of(LoomHost)} over the starter's host.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Import(LoomWorkflowsRegistrar.class)
public @interface EnableLoomWorkflows {
    /** The generated facade interfaces. */
    Class<?>[] value();
}
