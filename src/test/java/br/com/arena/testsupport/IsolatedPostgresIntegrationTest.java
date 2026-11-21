package br.com.arena.testsupport;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca todo teste que inicializa Spring e pode acessar o PostgreSQL/Flyway.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@SpringBootTest
@ContextConfiguration(initializers = TestPostgresContextInitializer.class)
public @interface IsolatedPostgresIntegrationTest {
}
