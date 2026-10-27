package io.github.llm4j.spring;

import java.util.Map;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/** Registers the facades named by {@link EnableLoomWorkflows}. */
final class LoomWorkflowsRegistrar
        implements ImportBeanDefinitionRegistrar, org.springframework.context.EnvironmentAware {

    private org.springframework.core.env.Environment environment;

    @Override
    public void setEnvironment(org.springframework.core.env.Environment environment) {
        this.environment = environment;
    }

    @Override
    public void registerBeanDefinitions(
            AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        // the facades are built over the starter's host: with no script, or the starter off, there
        // is none
        if (environment != null
                && (!environment.getProperty("llm4j.enabled", Boolean.class, true)
                        || !environment.containsProperty("llm4j.loom.script"))) return;
        Map<String, Object> attrs =
                metadata.getAnnotationAttributes(EnableLoomWorkflows.class.getName());
        if (attrs == null) return;
        for (Class<?> facade : (Class<?>[]) attrs.get("value")) {
            String name = "loomWorkflows." + facade.getName();
            if (registry.containsBeanDefinition(name)) continue;
            registry.registerBeanDefinition(
                    name,
                    BeanDefinitionBuilder.genericBeanDefinition(LoomWorkflowFactory.class)
                            .addConstructorArgValue(facade)
                            .getBeanDefinition());
        }
    }
}
