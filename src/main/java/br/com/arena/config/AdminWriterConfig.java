package br.com.arena.config;

import br.com.arena.service.AdminWriterJdbcService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Configura o datasource independente de {@code arena_admin_writer}.
 *
 * <p>O pool é criado internamente e entregue apenas ao
 * {@link AdminWriterJdbcService}. Nenhum {@code DataSource} ou
 * {@code JdbcTemplate} adicional é publicado como bean, evitando que a
 * auto-configuração do datasource de runtime/JPA/Flyway seja deslocada.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminWriterProperties.class)
public class AdminWriterConfig {

    private static final String EXPECTED_DATABASE_USER = "arena_admin_writer";

    @Bean(destroyMethod = "close")
    AdminWriterJdbcService adminWriterJdbcService(AdminWriterProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("arena-admin-writer-pool");
        config.setJdbcUrl(properties.url());
        config.setUsername(properties.username());
        config.setPassword(properties.password());
        config.setMaximumPoolSize(properties.maximumPoolSize());
        config.setMinimumIdle(properties.minimumIdle());
        config.setConnectionTimeout(properties.connectionTimeoutMs());
        config.setInitializationFailTimeout(properties.connectionTimeoutMs());
        config.setAutoCommit(true);
        config.setReadOnly(false);

        HikariDataSource dataSource = new HikariDataSource(config);
        try {
            assertConnectedAsAdminWriter(dataSource);
            return new AdminWriterJdbcService(dataSource);
        } catch (RuntimeException ex) {
            dataSource.close();
            throw ex;
        }
    }

    /**
     * Confirma, na inicialização, que o pool não foi acidentalmente apontado para
     * outra role. Não usa {@code SET ROLE} nem membership.
     */
    private static void assertConnectedAsAdminWriter(HikariDataSource dataSource) {
        String currentUser = new JdbcTemplate(dataSource)
                .queryForObject("SELECT current_user", String.class);

        if (!EXPECTED_DATABASE_USER.equals(currentUser)) {
            throw new IllegalStateException(
                    "Admin writer datasource must connect as " + EXPECTED_DATABASE_USER
                            + ", but connected as " + currentUser + "."
            );
        }
    }
}
