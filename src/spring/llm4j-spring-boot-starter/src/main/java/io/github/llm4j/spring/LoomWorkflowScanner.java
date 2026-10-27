package io.github.llm4j.spring;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/** Finds interfaces annotated {@link LoomWorkflows} in the application's packages. */
final class LoomWorkflowScanner implements BeanDefinitionRegistryPostProcessor, BeanFactoryAware {

    private BeanFactory beans;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) {
        this.beans = beanFactory;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry)
            throws BeansException {
        if (!AutoConfigurationPackages.has(beans)) return;
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition d) {
                        return d.getMetadata().isInterface();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(LoomWorkflows.class));
        for (String pkg : AutoConfigurationPackages.get(beans))
            for (BeanDefinition d : scanner.findCandidateComponents(pkg)) {
                String name = "loomWorkflows." + d.getBeanClassName();
                if (registry.containsBeanDefinition(name)) continue;
                try {
                    Class<?> type =
                            Class.forName(
                                    d.getBeanClassName(),
                                    false,
                                    Thread.currentThread().getContextClassLoader());
                    registry.registerBeanDefinition(
                            name,
                            BeanDefinitionBuilder.genericBeanDefinition(LoomWorkflowFactory.class)
                                    .addConstructorArgValue(type)
                                    .getBeanDefinition());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(e);
                }
            }
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {}
}
