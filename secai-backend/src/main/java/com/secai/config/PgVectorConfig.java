package com.secai.config;

import com.pgvector.PGvector;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

@Configuration
public class PgVectorConfig {

    /**
     * Register pgvector type with the PostgreSQL JDBC driver on startup.
     * Without this, the driver cannot deserialize VECTOR columns.
     */
    @Bean
    public boolean registerPgVector(DataSource dataSource) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            PGvector.addVectorType(conn);
        }
        return true;
    }
}