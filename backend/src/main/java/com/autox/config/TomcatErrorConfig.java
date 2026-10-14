package com.autox.config;

import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Installs {@link ProblemJsonErrorReportValve} for errors raised below Spring.
 *
 * <p>Ordered last on purpose. Spring Boot adds Tomcat's stock error valve to the same
 * pipeline through its own customizer; valves report from the innermost outwards, and the
 * one added last is innermost. Running after Boot is what makes this valve the one that
 * writes the response, with the stock valve finding the error already reported.
 */
@Configuration
@Order(Ordered.LOWEST_PRECEDENCE)
public class TomcatErrorConfig implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        factory.addContextCustomizers(context ->
                context.getParent().getPipeline().addValve(new ProblemJsonErrorReportValve()));
    }
}
