package com.poseidon.codegraph.app.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.support.JdbcTransactionManager;

@Configuration
public class RepositoryTransactionConfiguration {
    @Bean
    public JdbcTransactionManager repositoryTransactionManager(DataSource dataSource) {
        return new JdbcTransactionManager(dataSource);
    }
}
