package br.com.arena.testsupport;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.HashMap;
import java.util.Map;

/**
 * Executa antes do refresh do contexto, portanto antes de criar DataSource/Flyway.
 */
public class TestPostgresContextInitializer
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final String PROPERTY_SOURCE_NAME = "isolatedPostgresTestProperties";

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        initialize(applicationContext, System.getenv());
    }

    void initialize(
            ConfigurableApplicationContext applicationContext,
            Map<String, String> environment) {
        TestPostgresSettings settings = TestPostgresSettings.from(environment);

        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", settings.url());
        properties.put("spring.datasource.username", settings.username());
        properties.put("spring.datasource.password", settings.password());
        properties.put("spring.flyway.enabled", "true");
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("jwt.secret", settings.jwtSecret());
        properties.put(
                "jwt.expiration",
                Long.toString(settings.jwtExpirationMillis())
        );

        applicationContext.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }
}
