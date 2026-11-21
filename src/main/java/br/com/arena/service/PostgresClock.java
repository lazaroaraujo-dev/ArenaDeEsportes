package br.com.arena.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class PostgresClock {

    private final JdbcTemplate jdbcTemplate;

    public PostgresClock(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Deve ser chamado dentro da transação e somente depois dos locks necessários.
     */
    public Instant agora() {
        return jdbcTemplate.queryForObject(
                "SELECT clock_timestamp()",
                (resultSet, rowNum) -> resultSet.getTimestamp(1).toInstant()
        );
    }
}
