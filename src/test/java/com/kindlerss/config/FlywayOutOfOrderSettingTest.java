package com.kindlerss.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Out-of-order migrations hide a branch that skipped a version, so they stay off
 * unless an operator turns them on for a repair deploy.
 */
class FlywayOutOfOrderSettingTest {

    @Test
    void outOfOrderIsOffByDefault() throws Exception {
        assertEquals("false", outOfOrder(Map.of()));
    }

    @Test
    void outOfOrderCanBeTurnedOnFromTheEnvironment() throws Exception {
        assertEquals("true", outOfOrder(Map.of("FLYWAY_OUT_OF_ORDER", "true")));
    }

    private static String outOfOrder(Map<String, Object> env) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().addFirst(new MapPropertySource("env", env));
        List<PropertySource<?>> yaml = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"));
        yaml.forEach(environment.getPropertySources()::addLast);
        return environment.getProperty("spring.flyway.out-of-order");
    }
}
