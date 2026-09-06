package com.poseidon.codegraph.app.task;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Adds task-coordination columns for databases created by an older app version. */
@Component
@DependsOnDatabaseInitialization
public final class AnalysisTaskSchema {

    public AnalysisTaskSchema(JdbcTemplate jdbc) {
        Set<String> columns = columns(jdbc.getDataSource(), "analysis_task");
        addColumn(jdbc, columns, "attempt_count", "INT NOT NULL DEFAULT 0");
        addColumn(jdbc, columns, "max_attempts", "INT NOT NULL DEFAULT 3");
        addColumn(jdbc, columns, "lease_owner", "VARCHAR(255)");
        addColumn(jdbc, columns, "lease_until", "TIMESTAMP NULL");
        addColumn(jdbc, columns, "heartbeat_at", "TIMESTAMP NULL");
        addColumn(jdbc, columns, "next_attempt_at", "TIMESTAMP NULL");
        addColumn(jdbc, columns, "cancel_requested", "BOOLEAN NOT NULL DEFAULT FALSE");
        addColumn(jdbc, columns, "updated_at", "TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP");
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS analysis_worker (
                worker_id VARCHAR(255) PRIMARY KEY,
                host_name VARCHAR(255) NOT NULL,
                process_id BIGINT NOT NULL,
                status VARCHAR(32) NOT NULL,
                active_task_id VARCHAR(36),
                started_at TIMESTAMP NOT NULL,
                heartbeat_at TIMESTAMP NOT NULL,
                stopped_at TIMESTAMP NULL,
                last_error TEXT
            )
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS analysis_task_event (
                id VARCHAR(36) PRIMARY KEY,
                task_id VARCHAR(36) NOT NULL,
                stage VARCHAR(64) NOT NULL,
                status VARCHAR(32) NOT NULL,
                message VARCHAR(1024),
                details TEXT,
                started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                finished_at TIMESTAMP NULL,
                CONSTRAINT fk_analysis_task_event_task
                    FOREIGN KEY (task_id) REFERENCES analysis_task(id) ON DELETE CASCADE
            )
            """);
    }

    private static void addColumn(JdbcTemplate jdbc, Set<String> columns, String name, String definition) {
        if (!columns.contains(name)) {
            try {
                jdbc.execute("ALTER TABLE analysis_task ADD COLUMN " + name + " " + definition);
            } catch (DataAccessException exception) {
                Set<String> current = columns(jdbc.getDataSource(), "analysis_task");
                if (!current.contains(name)) throw exception;
            }
            columns.add(name);
        }
    }

    private static Set<String> columns(DataSource dataSource, String tableName) {
        if (dataSource == null) {
            throw new IllegalStateException("DataSource is required for task schema initialization");
        }
        Set<String> columns = new HashSet<>();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            collectColumns(metadata, connection.getCatalog(), tableName, columns);
            collectColumns(metadata, connection.getCatalog(), tableName.toUpperCase(Locale.ROOT), columns);
        } catch (SQLException exception) {
            throw new IllegalStateException("Cannot inspect analysis_task schema", exception);
        }
        return columns;
    }

    private static void collectColumns(
            DatabaseMetaData metadata,
            String catalog,
            String tableName,
            Set<String> columns) throws SQLException {
        try (ResultSet result = metadata.getColumns(catalog, null, tableName, null)) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
    }
}
