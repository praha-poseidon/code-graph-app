package com.poseidon.codegraph.app.task;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Repository
public class AnalysisWorkerStore {

    private final JdbcTemplate jdbc;
    private final RowMapper<AnalysisWorker> rowMapper = (rs, rowNum) -> new AnalysisWorker(
        rs.getString("worker_id"), rs.getString("host_name"), rs.getLong("process_id"),
        rs.getString("status"), rs.getString("active_task_id"),
        instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("heartbeat_at")),
        instant(rs.getTimestamp("stopped_at")), rs.getString("last_error"));

    public AnalysisWorkerStore(JdbcTemplate jdbc, AnalysisTaskSchema ignoredSchemaDependency) {
        this.jdbc = jdbc;
    }

    public void register(AnalysisWorkerIdentity identity) {
        Instant now = Instant.now();
        int updated = jdbc.update("""
            UPDATE analysis_worker
               SET host_name = ?, process_id = ?, status = 'IDLE', active_task_id = NULL,
                   started_at = ?, heartbeat_at = ?, stopped_at = NULL, last_error = NULL
             WHERE worker_id = ?
            """, identity.hostName(), identity.processId(), timestamp(now), timestamp(now), identity.workerId());
        if (updated == 0) {
            try {
                jdbc.update("""
                    INSERT INTO analysis_worker
                        (worker_id, host_name, process_id, status, started_at, heartbeat_at)
                    VALUES (?, ?, ?, 'IDLE', ?, ?)
                    """, identity.workerId(), identity.hostName(), identity.processId(),
                    timestamp(now), timestamp(now));
            } catch (DuplicateKeyException exception) {
                jdbc.update("""
                    UPDATE analysis_worker
                       SET host_name = ?, process_id = ?, status = 'IDLE', active_task_id = NULL,
                           started_at = ?, heartbeat_at = ?, stopped_at = NULL, last_error = NULL
                     WHERE worker_id = ?
                    """, identity.hostName(), identity.processId(), timestamp(now), timestamp(now),
                    identity.workerId());
            }
        }
    }

    public void heartbeat(String workerId, String activeTaskId) {
        jdbc.update("""
            UPDATE analysis_worker
               SET status = ?, active_task_id = ?, heartbeat_at = CURRENT_TIMESTAMP,
                   stopped_at = NULL
             WHERE worker_id = ?
            """, activeTaskId == null ? "IDLE" : "WORKING", activeTaskId, workerId);
    }

    public void recordError(String workerId, String details) {
        jdbc.update("UPDATE analysis_worker SET last_error = ? WHERE worker_id = ?", details, workerId);
    }

    public void markOffline(String workerId) {
        jdbc.update("""
            UPDATE analysis_worker
               SET status = 'OFFLINE', active_task_id = NULL, stopped_at = CURRENT_TIMESTAMP
             WHERE worker_id = ?
            """, workerId);
    }

    public int markStaleOffline(Duration staleAfter) {
        return jdbc.update("""
            UPDATE analysis_worker
               SET status = 'OFFLINE', active_task_id = NULL, stopped_at = CURRENT_TIMESTAMP
             WHERE status <> 'OFFLINE' AND heartbeat_at < ?
            """, timestamp(Instant.now().minus(staleAfter)));
    }

    public List<AnalysisWorker> findAll() {
        return jdbc.query("SELECT * FROM analysis_worker ORDER BY heartbeat_at DESC", rowMapper);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
