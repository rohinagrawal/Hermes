package com.flauntik.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Optional MySQL connection settings for a persistent {@code SessionStore}. Absent from
 * {@code HermesConfig} entirely (the {@code database} key is simply missing from
 * config.json) means conversation sessions stay in-memory-only, exactly as before this
 * was added - see {@code repository.jdbc.JdbcSessionStore}.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DatabaseConfig {
    private String jdbcUrl;
    private String username;
    private String password;
    private Integer maximumPoolSize = 10;
}
