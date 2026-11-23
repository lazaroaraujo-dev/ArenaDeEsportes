package br.com.arena.cli;

import br.com.arena.cli.input.ConsoleInputReader;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * Aplicação CLI offline para bootstrap e recovery administrativo do H-04.
 * Não inicializa servidor web, scheduling, HttpSecurity, JWT, OpenAPI,
 * DataSource da aplicação, JPA, Flyway nem controllers da aplicação web.
 */
@Configuration
@ComponentScan("br.com.arena.cli")
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration"
})
public class AdminCliApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(AdminCliApplication.class)
                .profiles("cli")
                .web(WebApplicationType.NONE)
                .logStartupInfo(false)
                .properties(
                        "spring.autoconfigure.exclude=" +
                                "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
                                "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
                                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"
                )
                .run(args);
    }

    @Bean
    CliRunner adminCliRunner() {
        return new CliRunner(CliEnvironment.from(System.getenv()), new ConsoleInputReader());
    }
}
