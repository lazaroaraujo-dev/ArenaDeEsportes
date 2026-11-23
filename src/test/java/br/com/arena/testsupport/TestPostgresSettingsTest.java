package br.com.arena.testsupport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.util.ClassUtils;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestPostgresSettingsTest {

    private static final String SAFE_URL =
            "jdbc:postgresql://127.0.0.1:54329/arena_flyway_test";

    @Test
    void acceptsOnlyTheDedicatedLocalTestDatabase() {
        TestPostgresSettings settings = TestPostgresSettings.from(validTestEnvironment());

        assertEquals(SAFE_URL, settings.url());
        assertEquals(54329, settingsPort(settings.url()));
    }

    @Test
    void rejectsMissingTestVariablesWithoutFallingBackToSpringDatasource() {
        Map<String, String> onlySpringDatasource = Map.of(
                "SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/arena"
        );

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(onlySpringDatasource)
        );

        assertTrue(error.getMessage().contains("TEST_POSTGRES_URL"));
        assertFalse(error.getMessage().contains("jdbc:postgresql"));
    }

    @Test
    void rejectsProductionJwtAndSpringVariablesAsFallback() {
        Map<String, String> productionFallback = new HashMap<>();
        productionFallback.put("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/arena");
        productionFallback.put("SPRING_DATASOURCE_USERNAME", "arena_runtime");
        productionFallback.put("SPRING_DATASOURCE_PASSWORD", "production-runtime-secret");
        productionFallback.put("SPRING_FLYWAY_URL", "jdbc:postgresql://127.0.0.1:5432/arena");
        productionFallback.put("SPRING_FLYWAY_USER", "arena_migrator");
        productionFallback.put("SPRING_FLYWAY_PASSWORD", "production-migrator-secret");
        productionFallback.put("JWT_SECRET", "a".repeat(32));
        productionFallback.put("JWT_EXPIRATION_MS", "3600000");

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(productionFallback)
        );

        assertTrue(error.getMessage().contains("TEST_POSTGRES_URL"));
    }

    @Test
    void rejectsDevelopmentDatabasesExternalHostsLocalhostAndUrlParameters() {
        for (String url : new String[]{
                "jdbc:postgresql://127.0.0.1:5432/arena",
                "jdbc:postgresql://127.0.0.1:5432/arena_dev",
                "jdbc:postgresql://db.example.invalid:5432/arena_flyway_test",
                "jdbc:postgresql://localhost:5432/arena_flyway_test",
                "jdbc:postgresql://127.0.0.1:5432/arena_flyway_test?targetServerType=primary",
                "jdbc:postgresql://127.0.0.1:65536/arena_flyway_test",
                "jdbc:postgresql://127.0.0.1:0/arena_flyway_test"
        }) {
            Map<String, String> environment = validTestEnvironment();
            environment.put("TEST_POSTGRES_URL", url);
            assertThrows(
                    IllegalStateException.class,
                    () -> TestPostgresSettings.from(environment),
                    "URL should be rejected: " + url
            );
        }
    }

    @Test
    void requiresCredentialsAndValidJwtSettingsWithoutIncludingValuesInErrors() {
        Map<String, String> missingPassword = validTestEnvironment();
        missingPassword.remove("TEST_POSTGRES_RUNTIME_PASSWORD");
        IllegalStateException passwordError = assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(missingPassword)
        );
        assertTrue(passwordError.getMessage().contains("TEST_POSTGRES_RUNTIME_PASSWORD"));

        Map<String, String> invalidJwt = validTestEnvironment();
        invalidJwt.put("TEST_JWT_SECRET", "short");
        IllegalStateException jwtError = assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(invalidJwt)
        );
        assertTrue(jwtError.getMessage().contains("TEST_JWT_SECRET"));
        assertFalse(jwtError.getMessage().contains("short"));
    }

    @Test
    void runtimeAndMigratorMustUseDistinctDedicatedRolesAndPasswords() {
        Map<String, String> sameRole = validTestEnvironment();
        sameRole.put("TEST_POSTGRES_MIGRATOR_USERNAME", "arena_runtime");
        assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(sameRole)
        );

        Map<String, String> samePassword = validTestEnvironment();
        samePassword.put(
                "TEST_POSTGRES_MIGRATOR_PASSWORD",
                samePassword.get("TEST_POSTGRES_RUNTIME_PASSWORD")
        );
        assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(samePassword)
        );
    }

    @Test
    void writerAndOfflineMustExistWithDistinctPasswordsAndNoFallback() {
        TestPostgresSettings settings = TestPostgresSettings.from(validTestEnvironment());

        assertEquals("arena_admin_writer", settings.writerUsername());
        assertEquals("arena_offline_operator", settings.offlineUsername());
        assertFalse(settings.writerPassword().equals(settings.runtimePassword()));
        assertFalse(settings.writerPassword().equals(settings.migratorPassword()));
        assertFalse(settings.writerPassword().equals(settings.offlinePassword()));
        assertFalse(settings.offlinePassword().equals(settings.runtimePassword()));
        assertFalse(settings.offlinePassword().equals(settings.migratorPassword()));

        Map<String, String> missingWriter = validTestEnvironment();
        missingWriter.remove("TEST_POSTGRES_WRITER_PASSWORD");
        assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(missingWriter)
        );

        Map<String, String> writerEqualsOffline = validTestEnvironment();
        writerEqualsOffline.put("TEST_POSTGRES_OFFLINE_PASSWORD", writerEqualsOffline.get("TEST_POSTGRES_WRITER_PASSWORD"));
        assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(writerEqualsOffline)
        );
    }

    @Test
    void h04InitialInstallDefaultsToFalseAndOnlyAcceptsTrueOrFalse() {
        TestPostgresSettings settings = TestPostgresSettings.from(validTestEnvironment());
        assertEquals("false", settings.h04InitialInstall());

        Map<String, String> explicitTrue = validTestEnvironment();
        explicitTrue.put("TEST_H04_INITIAL_INSTALL", "true");
        assertEquals("true", TestPostgresSettings.from(explicitTrue).h04InitialInstall());

        Map<String, String> invalidValue = validTestEnvironment();
        invalidValue.put("TEST_H04_INITIAL_INSTALL", "yes");
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> TestPostgresSettings.from(invalidValue)
        );
        assertTrue(error.getMessage().contains("TEST_H04_INITIAL_INSTALL"));

        Map<String, String> productionFallback = validTestEnvironment();
        productionFallback.remove("TEST_H04_INITIAL_INSTALL");
        productionFallback.put("H04_INITIAL_INSTALL", "true");
        assertEquals("false", TestPostgresSettings.from(productionFallback).h04InitialInstall());
    }

    @Test
    void initializerOverridesSpringDatasourceAndDoesNotRefreshBeforeValidation() {
        GenericApplicationContext validContext = new GenericApplicationContext();
        validContext.getEnvironment().getPropertySources().addLast(
                new MapPropertySource(
                        "normalApplicationProperties",
                        Map.of(
                                "spring.datasource.url",
                                "jdbc:postgresql://127.0.0.1:5432/arena",
                                "spring.flyway.enabled",
                                "true"
                        )
                )
        );

        new TestPostgresContextInitializer()
                .initialize(validContext, validTestEnvironment());

        assertEquals(
                SAFE_URL,
                validContext.getEnvironment().getProperty("spring.datasource.url")
        );
        assertEquals(
                "true",
                validContext.getEnvironment().getProperty("spring.flyway.enabled")
        );
        assertFalse(validContext.isActive());

        GenericApplicationContext invalidContext = new GenericApplicationContext();
        invalidContext.getEnvironment().getPropertySources().addLast(
                new MapPropertySource(
                        "normalApplicationProperties",
                        Map.of(
                                "spring.datasource.url",
                                "jdbc:postgresql://127.0.0.1:5432/arena",
                                "spring.flyway.enabled",
                                "true"
                        )
                )
        );
        Map<String, String> missingTestEnvironment = Map.of(
                "SPRING_DATASOURCE_URL",
                "jdbc:postgresql://127.0.0.1:5432/arena"
        );

        assertThrows(
                IllegalStateException.class,
                () -> new TestPostgresContextInitializer()
                        .initialize(invalidContext, missingTestEnvironment)
        );
        assertFalse(invalidContext.isActive());
        assertEquals(
                "jdbc:postgresql://127.0.0.1:5432/arena",
                invalidContext.getEnvironment().getProperty("spring.datasource.url")
        );
    }

    @Test
    void everySpringContextTestUsesTheCentralAnnotation() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(SpringBootTest.class, true));
        scanner.addIncludeFilter(new AnnotationTypeFilter(ContextConfiguration.class, true));

        Set<String> classNames = new HashSet<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("br.com.arena")) {
            classNames.add(candidate.getBeanClassName());
        }

        assertTrue(classNames.contains("br.com.arena.ArenaApplicationTests"));
        for (String className : classNames) {
            Class<?> candidate = ClassUtils.forName(className, getClass().getClassLoader());
            if (candidate.isAnnotation()) {
                continue;
            }
            assertTrue(
                    AnnotatedElementUtils.hasAnnotation(
                            candidate,
                            IsolatedPostgresIntegrationTest.class
                    ),
                    className + " must use the central PostgreSQL test guard"
            );
        }
    }

    private static Map<String, String> validTestEnvironment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("TEST_POSTGRES_URL", SAFE_URL);
        environment.put("TEST_POSTGRES_RUNTIME_USERNAME", "arena_runtime");
        environment.put("TEST_POSTGRES_RUNTIME_PASSWORD", "r".repeat(32));
        environment.put("TEST_POSTGRES_MIGRATOR_USERNAME", "arena_migrator");
        environment.put("TEST_POSTGRES_MIGRATOR_PASSWORD", "m".repeat(32));
        environment.put("TEST_POSTGRES_WRITER_USERNAME", "arena_admin_writer");
        environment.put("TEST_POSTGRES_WRITER_PASSWORD", "w".repeat(32));
        environment.put("TEST_POSTGRES_OFFLINE_USERNAME", "arena_offline_operator");
        environment.put("TEST_POSTGRES_OFFLINE_PASSWORD", "o".repeat(32));
        environment.put("TEST_JWT_SECRET", "x".repeat(32));
        environment.put("TEST_JWT_EXPIRATION_MS", "3600000");
        environment.put("TEST_H04_INITIAL_INSTALL", "false");
        return environment;
    }

    private static int settingsPort(String url) {
        return Integer.parseInt(
                url.substring(url.indexOf(':', "jdbc:postgresql://".length()) + 1,
                        url.lastIndexOf('/'))
        );
    }
}
