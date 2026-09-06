package com.poseidon.codegraph.app.task;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AnalysisTaskStore {

    private final JdbcTemplate jdbc;
    private final AnalysisTaskEventStore eventStore;
    private final int defaultMaxAttempts;
    private final RowMapper<AnalysisTask> rowMapper = (rs, rowNum) -> new AnalysisTask(
        rs.getString("id"), rs.getLong("repository_id"), rs.getString("status"),
        rs.getInt("progress_current"), rs.getInt("progress_total"),
        rs.getString("message"), rs.getString("error_details"),
        rs.getInt("attempt_count"), rs.getInt("max_attempts"), rs.getString("lease_owner"),
        instant(rs.getTimestamp("lease_until")), instant(rs.getTimestamp("heartbeat_at")),
        instant(rs.getTimestamp("next_attempt_at")), rs.getBoolean("cancel_requested"),
        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("started_at")),
        instant(rs.getTimestamp("finished_at")), instant(rs.getTimestamp("updated_at"))
    );

    public AnalysisTaskStore(
            JdbcTemplate jdbc,
            AnalysisTaskSchema ignoredSchemaDependency,
            AnalysisTaskEventStore eventStore,
            @Value("${code-graph.tasks.max-attempts:3}") int defaultMaxAttempts) {
        this.jdbc = jdbc;
        this.eventStore = eventStore;
        this.defaultMaxAttempts = Math.max(1, defaultMaxAttempts);
    }

    public AnalysisTask enqueue(long repositoryId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO analysis_task (id, repository_id, status, message, max_attempts, updated_at)
            VALUES (?, ?, 'QUEUED', '等待执行', ?, CURRENT_TIMESTAMP)
            """, id, repositoryId, defaultMaxAttempts);
        eventStore.record(id, "QUEUED", "SUCCEEDED", "任务已进入队列", null);
        return findById(id).orElseThrow();
    }

    public Optional<AnalysisTask> findById(String id) {
        return jdbc.query("SELECT * FROM analysis_task WHERE id = ?", rowMapper, id).stream().findFirst();
    }

    public List<AnalysisTask> findByRepository(long repositoryId) {
        return jdbc.query("""
            SELECT * FROM analysis_task WHERE repository_id = ?
             ORDER BY created_at DESC LIMIT 50
            """, rowMapper, repositoryId);
    }

    public List<AnalysisTask> findRecent() {
        return jdbc.query("SELECT * FROM analysis_task ORDER BY created_at DESC LIMIT 100", rowMapper);
    }

    public Optional<AnalysisTask> latestForRepository(long repositoryId) {
        return jdbc.query("""
            SELECT * FROM analysis_task WHERE repository_id = ?
             ORDER BY created_at DESC LIMIT 1
            """, rowMapper, repositoryId).stream().findFirst();
    }

    public Optional<AnalysisTask> activeForRepository(long repositoryId) {
        return jdbc.query("""
            SELECT * FROM analysis_task
             WHERE repository_id = ? AND status IN ('QUEUED', 'RUNNING')
             ORDER BY created_at DESC LIMIT 1
            """, rowMapper, repositoryId).stream().findFirst();
    }

    /** The conditional update is a database compare-and-set across app instances. */
    public Optional<AnalysisTask> claimNext(String workerId, Duration leaseDuration) {
        Instant now = Instant.now();
        List<String> ids = jdbc.queryForList("""
            SELECT t.id FROM analysis_task t
             WHERE t.status = 'QUEUED' AND t.cancel_requested = FALSE
               AND (t.next_attempt_at IS NULL OR t.next_attempt_at <= ?)
               AND NOT EXISTS (
                   SELECT 1 FROM analysis_task active
                    WHERE active.repository_id = t.repository_id
                      AND active.status = 'RUNNING' AND active.lease_until > ?
               )
             ORDER BY t.created_at ASC, t.id ASC LIMIT 1
            """, String.class, timestamp(now), timestamp(now));
        if (ids.isEmpty()) return Optional.empty();

        String id = ids.getFirst();
        int updated = jdbc.update("""
            UPDATE analysis_task
               SET status = 'RUNNING', started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                   finished_at = NULL, attempt_count = attempt_count + 1,
                   lease_owner = ?, lease_until = ?, heartbeat_at = ?, next_attempt_at = NULL,
                   error_details = NULL, message = '准备工作目录', updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'QUEUED' AND cancel_requested = FALSE
            """, workerId, timestamp(now.plus(leaseDuration)), timestamp(now), id);
        return updated == 1 ? findById(id) : Optional.empty();
    }

    /** Only expired leases are recovered; a healthy worker's tasks are never reset at startup. */
    public int recoverExpiredLeases() {
        Timestamp now = timestamp(Instant.now());
        int canceled = jdbc.update("""
            UPDATE analysis_task
               SET status = 'CANCELED', message = '任务已取消', finished_at = CURRENT_TIMESTAMP,
                   lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE status = 'RUNNING' AND (lease_until IS NULL OR lease_until < ?)
               AND cancel_requested = TRUE
            """, now);
        int retried = jdbc.update("""
            UPDATE analysis_task
               SET status = 'QUEUED', message = 'Worker 租约过期，等待重试',
                   lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                   next_attempt_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
             WHERE status = 'RUNNING' AND (lease_until IS NULL OR lease_until < ?)
               AND cancel_requested = FALSE AND attempt_count < max_attempts
            """, now);
        int failed = jdbc.update("""
            UPDATE analysis_task
               SET status = 'FAILED', message = 'Worker 租约过期且已达到最大重试次数',
                   error_details = 'No heartbeat received before lease expiry',
                   finished_at = CURRENT_TIMESTAMP,
                   lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE status = 'RUNNING' AND (lease_until IS NULL OR lease_until < ?)
               AND cancel_requested = FALSE AND attempt_count >= max_attempts
            """, now);
        return canceled + retried + failed;
    }

    public boolean renewLease(String id, String workerId, Duration leaseDuration) {
        Instant now = Instant.now();
        return jdbc.update("""
            UPDATE analysis_task SET lease_until = ?, heartbeat_at = ?, updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
            """, timestamp(now.plus(leaseDuration)), timestamp(now), id, workerId) == 1;
    }

    public boolean updateProgress(String id, String workerId, int current, int total, String message) {
        return jdbc.update("""
            UPDATE analysis_task
               SET progress_current = ?, progress_total = ?, message = ?, updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
            """, current, total, message, id, workerId) == 1;
    }

    public boolean succeed(String id, String workerId, int total) {
        return jdbc.update("""
            UPDATE analysis_task
               SET status = 'SUCCEEDED', progress_current = ?, progress_total = ?,
                   message = '分析完成', finished_at = CURRENT_TIMESTAMP,
                   lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'RUNNING' AND lease_owner = ? AND cancel_requested = FALSE
            """, total, total, id, workerId) == 1;
    }

    public TaskFailureDisposition failOrRetry(
            String id, String workerId, String message, String details, Duration retryDelay) {
        AnalysisTask task = findById(id).orElse(null);
        if (task == null || !"RUNNING".equals(task.status()) || !workerId.equals(task.leaseOwner())) {
            return TaskFailureDisposition.LEASE_LOST;
        }
        if (task.cancelRequested()) {
            return markCanceled(id, workerId, "任务已取消")
                ? TaskFailureDisposition.CANCELED : TaskFailureDisposition.LEASE_LOST;
        }
        if (task.attemptCount() < task.maxAttempts()) {
            int updated = jdbc.update("""
                UPDATE analysis_task
                   SET status = 'QUEUED', message = ?, error_details = ?, next_attempt_at = ?,
                       lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                       updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
                   AND cancel_requested = FALSE
                """, message + "，等待重试", details,
                timestamp(Instant.now().plus(retryDelay)), id, workerId);
            if (updated == 1) return TaskFailureDisposition.RETRY_SCHEDULED;
            return cancelIfRequested(id, workerId);
        }
        int updated = jdbc.update("""
            UPDATE analysis_task
               SET status = 'FAILED', message = ?, error_details = ?, finished_at = CURRENT_TIMESTAMP,
                   lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
               AND cancel_requested = FALSE
            """, message, details, id, workerId);
        if (updated == 1) return TaskFailureDisposition.FAILED;
        return cancelIfRequested(id, workerId);
    }

    public Optional<AnalysisTask> cancel(String id) {
        int queued = jdbc.update("""
            UPDATE analysis_task
               SET status = 'CANCELED', cancel_requested = TRUE, message = '任务已取消',
                   finished_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
            WHERE id = ? AND status = 'QUEUED'
            """, id);
        if (queued == 1) {
            eventStore.record(id, "COMPLETE", "CANCELED", "任务已取消", null);
        }
        if (queued == 0) {
            jdbc.update("""
                UPDATE analysis_task
                   SET cancel_requested = TRUE, message = '正在取消', updated_at = CURRENT_TIMESTAMP
                 WHERE id = ? AND status = 'RUNNING'
                """, id);
        }
        return findById(id);
    }

    public boolean markCanceled(String id, String workerId, String message) {
        return jdbc.update("""
            UPDATE analysis_task
               SET status = 'CANCELED', cancel_requested = TRUE, message = ?,
                   finished_at = CURRENT_TIMESTAMP,
                   lease_owner = NULL, lease_until = NULL, heartbeat_at = NULL,
                   updated_at = CURRENT_TIMESTAMP
             WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
            """, message, id, workerId) == 1;
    }

    public boolean isOwnedAndRunning(String id, String workerId) {
        Integer count = jdbc.queryForObject("""
            SELECT COUNT(*) FROM analysis_task
             WHERE id = ? AND status = 'RUNNING' AND lease_owner = ?
            """, Integer.class, id, workerId);
        return count != null && count == 1;
    }

    private TaskFailureDisposition cancelIfRequested(String id, String workerId) {
        AnalysisTask current = findById(id).orElse(null);
        if (current != null && current.cancelRequested()
                && "RUNNING".equals(current.status()) && workerId.equals(current.leaseOwner())) {
            return markCanceled(id, workerId, "任务已取消")
                ? TaskFailureDisposition.CANCELED : TaskFailureDisposition.LEASE_LOST;
        }
        return TaskFailureDisposition.LEASE_LOST;
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }
}
