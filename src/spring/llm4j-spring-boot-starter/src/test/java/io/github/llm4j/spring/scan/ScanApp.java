package io.github.llm4j.spring.scan;

import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.context.annotation.Configuration;

/** The application's root: its package is what is scanned for workflow interfaces. */
@Configuration(proxyBeanMethods = false)
@AutoConfigurationPackage
public class ScanApp {}
