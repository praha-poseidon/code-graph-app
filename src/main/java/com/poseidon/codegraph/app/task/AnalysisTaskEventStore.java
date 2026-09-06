package com.poseidon.codegraph.app.task;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class AnalysisTaskEventStore {

    private final JdbcTemplate jdbc;
    private final RowMapper<AnalysisTaskEvent> rowMapper = (rs, rowNum) -> new AnalysisTaskEvent(
        rs.getString("id"), rs.getString("task_id"), rs.getString("stage"), rs.getString("status"),
        rs.getString("message"), rs.getString("details"),
        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("finished_at"))
    );

    public AnalysisTaskEventStore(JdbcTemplate jdbc, AnalysisTaskSchema ignoredSchemaDependency) {
        this.jdbc = jdbc;
    }

    public String start(String taskId, String stage, String message) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO analysis_task_event (id, task_id, stage, status, message)
            VALUES (?, ?, ?, 'RUNNING', ?)
            """, id, taskId, stage, message);
        return id;
    }

    public void update(String eventId, String message) {
        if (eventId == null) return;
        jdbc.update("""
            UPDATE analysis_task_event SET message = ?
             WHERE id = ? AND status = 'RUNNING'
            """, message, eventId);
    }

    public void succeed(String eventId, String message) {
        finish(eventId, "SUCCEEDED", message, null);
    }

    public void fail(String eventId, String message, String details) {
        finish(eventId, "FAILED", message, details);
    }

    public void cancel(String eventId, String message) {
        finish(eventId, "CANCELED", message, null);
    }

    public void skip(String taskId, String stage, String message) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO analysis_task_event
                (id, task_id, stage, status, message, started_at, finished_at)
            VALUES (?, ?, ?, 'SKIPPED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, id, taskId, stage, message);
    }

    public void record(String taskId, String stage, String status, String message, String details) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO analysis_task_event
                (id, task_id, stage, status, message, details, started_at, finished_at)
            VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, id, taskId, stage, status, message, details);
    }

    public List<AnalysisTaskEvent> findByTaskId(String taskId) {
        return jdbc.query("""
            SELECT * FROM analysis_task_event WHERE task_id = ?
             ORDER BY started_at ASC, id ASC
            """, rowMapper, taskId);
    }

    private void finish(String eventId, String status, String message, String details) {
        if (eventId == null) return;
        jdbc.update("""
            UPDATE analysis_task_event
               SET status = ?, message = ?, details = ?, finished_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'RUNNING'
            """, status, message, details, eventId);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }
}
