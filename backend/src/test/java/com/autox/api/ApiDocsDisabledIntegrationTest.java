package com.autox.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.TestPropertySource;

/**
 * The API description and Swagger UI in production: not served. {@code application.yml} turns
 * springdoc off unless {@code AUTOX_API_DOCS_ENABLED} is set, which production does not set; a
 * local machine sets it in {@code .env}. Off, both answer 404, as any path that does not exist.
 */
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled = false",
        "springdoc.swagger-ui.enabled = false"})
class ApiDocsDisabledIntegrationTest extends AbstractIntegrationTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config",
            "/swagger-ui.html", "/swagger-ui/index.html"})
    @DisplayName("with the documentation off, as in production, each path answers 404")
    void notServed(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("application.yml turns both off when AUTOX_API_DOCS_ENABLED is not set, and on when it is")
    void offUnlessSwitchedOn() throws Exception {
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));

        MutablePropertySources production = new MutablePropertySources();
        yaml.forEach(production::addLast);
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(production);
        assertThat(resolver.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        assertThat(resolver.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isFalse();

        MutablePropertySources local = new MutablePropertySources();
        local.addFirst(new org.springframework.core.env.MapPropertySource("env",
                java.util.Map.of("AUTOX_API_DOCS_ENABLED", "true")));
        yaml.forEach(local::addLast);
        PropertySourcesPropertyResolver localResolver = new PropertySourcesPropertyResolver(local);
        assertThat(localResolver.getProperty("springdoc.api-docs.enabled", Boolean.class)).isTrue();
        assertThat(localResolver.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isTrue();
    }
}
